package org.alex_melan.obsidianauth.paper.enrollment;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.alex_melan.obsidianauth.core.qr.MapPaletteRasterizer;
import org.alex_melan.obsidianauth.core.qr.QrEncoder;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Delivers and later dismisses the QR card, using the slot-borrow-with-stash strategy
 * (FR-003): prefer the first empty hotbar slot, otherwise borrow the currently-held slot
 * and persist the displaced item to a crash-resistant stash file before the slot is touched.
 *
 * <p>The card is removed and any borrowed item restored on successful verification
 * ({@link #dismissCard}) and on player disconnect ({@link #dismissCardOnQuit}). The slot
 * the card occupies is tracked on the {@link PaperSession} so the card is removed even when
 * it went into a previously-empty slot (no stash entry to key off).
 *
 * <p>No method blocks the main / region thread: {@link #deliver} and {@link #dismissCard}
 * are pure {@code CompletableFuture} chains that hop async→sync→async; only
 * {@link #dismissCardOnQuit} runs synchronously, and only on {@code PlayerQuitEvent} where
 * the inventory MUST be restored before Paper writes the player file (it does a plain file
 * read + delete — no fsync, no future-blocking).
 */
public final class CardDeliveryService {

    private final AsyncExecutor async;
    private final SyncExecutor sync;
    private final SlotBorrowStash stash;
    private final Logger log;

    public CardDeliveryService(AsyncExecutor async, SyncExecutor sync,
                               SlotBorrowStash stash, Logger log) {
        this.async = async;
        this.sync = sync;
        this.stash = stash;
        this.log = log;
    }

    /** Card + chosen slot + (cloned) displaced item, carried between the chain's stages. */
    private record Pending(int mapId, ItemStack card, int slot, ItemStack displaced) {}

    /**
     * Deliver a QR card encoding {@code otpauthUri} to {@code player}. The returned future
     * completes once the card is in the player's hotbar.
     *
     * <p>Stage order (FR-003b): async QR encode → sync build map/card + pick slot + capture
     * the displaced item → async stash-save (fsync) → sync apply the slot mutation. The
     * displaced item is durably stashed BEFORE the inventory is touched.
     */
    public CompletableFuture<Void> deliver(Player player, String otpauthUri, PaperSession session) {
        UUID uuid = player.getUniqueId();
        return async.submit(() -> MapPaletteRasterizer.rasterize(QrEncoder.encode(otpauthUri)))
                .thenApplyAsync(grid -> {
                    // Main / region thread: allocate the map, build the card, choose a slot,
                    // and capture (a clone of) whatever currently occupies it. The inventory
                    // is NOT mutated yet — that waits until the displaced item is stashed.
                    MapView view = Bukkit.createMap(player.getWorld());
                    for (MapRenderer renderer : view.getRenderers()) {
                        view.removeRenderer(renderer);
                    }
                    view.addRenderer(new QrMapRenderer(grid));

                    ItemStack card = new ItemStack(Material.FILLED_MAP);
                    card.editMeta(meta -> {
                        if (meta instanceof MapMeta mapMeta) {
                            mapMeta.setMapView(view);
                            mapMeta.displayName(net.kyori.adventure.text.Component.text(
                                    "ObsidianAuth Setup Card",
                                    net.kyori.adventure.text.format.NamedTextColor.AQUA));
                        }
                    });

                    PlayerInventory inv = player.getInventory();
                    int slot = pickSlot(inv);
                    ItemStack occupant = inv.getItem(slot);
                    ItemStack displaced = (occupant != null && occupant.getType() != Material.AIR)
                            ? occupant.clone()
                            : null;
                    return new Pending(view.getId(), card, slot, displaced);
                }, sync.asExecutor())
                .thenCompose(pending -> {
                    // Off-main: persist the displaced item (fsync) before any slot mutation.
                    if (pending.displaced == null) {
                        return CompletableFuture.completedFuture(pending);
                    }
                    return stash.save(uuid, pending.slot, pending.displaced)
                            .thenApply(ignored -> pending);
                })
                .thenAcceptAsync(pending -> {
                    // Main / region thread: now that the borrowed item is durable, apply the
                    // slot mutation and record where the card landed on the session.
                    PlayerInventory inv = player.getInventory();
                    inv.setItem(pending.slot, pending.card);
                    inv.setHeldItemSlot(pending.slot);
                    session.setActiveMapId(Optional.of(pending.mapId));
                    session.setCardSlot(Optional.of(pending.slot));
                    if (pending.displaced != null) {
                        session.setStashedItemRef(Optional.of(uuid));
                    }
                }, sync.asExecutor());
    }

    /**
     * Remove the QR card and restore any borrowed item — for the verification-success path,
     * where the player is online. Pure {@code CompletableFuture} chain: async stash-load →
     * sync inventory mutation → async stash-delete.
     */
    public CompletableFuture<Void> dismissCard(Player player, PaperSession session) {
        UUID uuid = player.getUniqueId();
        boolean hadStash = session.stashedItemRef().isPresent();
        CompletableFuture<Optional<SlotBorrowStash.StashEntry>> loadStage = hadStash
                ? stash.load(uuid)
                : CompletableFuture.completedFuture(Optional.empty());
        return loadStage
                .thenAcceptAsync(maybeEntry -> applyDismissal(player, session, maybeEntry),
                        sync.asExecutor())
                .thenCompose(ignored -> {
                    if (!hadStash) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return stash.delete(uuid).thenApply(deleted -> (Void) null);
                });
    }

    /**
     * Synchronous card dismissal for {@code PlayerQuitEvent}: the inventory must be restored
     * before Paper saves the player's data file, so this cannot be deferred to a later tick.
     * It performs only a plain stash-file read + delete (no fsync, no future-blocking).
     */
    public void dismissCardOnQuit(Player player, PaperSession session) {
        UUID uuid = player.getUniqueId();
        Optional<SlotBorrowStash.StashEntry> maybeEntry = Optional.empty();
        if (session.stashedItemRef().isPresent()) {
            try {
                maybeEntry = stash.loadSync(uuid);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "could not read stash for quitting player " + uuid
                        + " — borrowed item not restored this session", e);
                maybeEntry = Optional.empty();
            }
        }
        applyDismissal(player, session, maybeEntry);
        if (maybeEntry.isPresent()) {
            try {
                stash.deleteSync(uuid);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "could not delete stash for quitting player " + uuid, e);
            }
        }
    }

    /**
     * Inventory mutation shared by both dismissal paths. MUST be called on the main / region
     * thread. Removes the FILLED_MAP from the card slot and puts the borrowed item back (or
     * clears the slot when nothing was borrowed), then clears the card bookkeeping.
     */
    private static void applyDismissal(Player player, PaperSession session,
                                       Optional<SlotBorrowStash.StashEntry> maybeEntry) {
        PlayerInventory inv = player.getInventory();
        // The stash entry's slot is authoritative; fall back to the slot recorded at delivery
        // (the empty-slot case has no stash entry but still records the card slot).
        int slot = maybeEntry.map(SlotBorrowStash.StashEntry::slotIndex)
                .or(session::cardSlot)
                .orElse(-1);
        if (slot >= 0 && slot < inv.getSize()) {
            ItemStack replacement = maybeEntry.map(SlotBorrowStash.StashEntry::item).orElse(null);
            ItemStack current = inv.getItem(slot);
            boolean cardPresent = current != null && current.getType() == Material.FILLED_MAP;
            if (cardPresent || replacement != null) {
                // Removing the card: setItem(slot, null) clears it; setItem(slot, replacement)
                // restores the borrowed item. Either way the card is gone afterwards.
                inv.setItem(slot, replacement);
            }
        }
        session.setCardSlot(Optional.empty());
        session.setActiveMapId(Optional.empty());
        session.setStashedItemRef(Optional.empty());
    }

    private static int pickSlot(PlayerInventory inv) {
        // Prefer the first empty hotbar slot (0..8).
        for (int i = 0; i < 9; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || slot.getType() == Material.AIR) {
                return i;
            }
        }
        // Hotbar full — borrow the currently-selected slot.
        return inv.getHeldItemSlot();
    }
}
