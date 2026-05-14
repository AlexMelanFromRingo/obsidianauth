package org.alex_melan.obsidianauth.paper.enrollment;

import java.util.concurrent.CompletableFuture;
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
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Delivers the QR card to a player using the slot-borrow-with-stash strategy:
 *
 * <ol>
 *   <li>Prefer the first empty hotbar slot.</li>
 *   <li>Otherwise borrow the player's currently-held slot, saving the displaced item to
 *       disk first (fsync'd) so a crash can't lose it.</li>
 * </ol>
 *
 * <p>Restoration runs on auth success / disconnect / startup pre-join scan; the calling
 * code wires those triggers in the lifecycle listeners.
 */
public final class CardDeliveryService {

    private final AsyncExecutor async;
    private final SyncExecutor sync;
    private final SlotBorrowStash stash;

    public CardDeliveryService(AsyncExecutor async, SyncExecutor sync, SlotBorrowStash stash) {
        this.async = async;
        this.sync = sync;
        this.stash = stash;
    }

    /**
     * Deliver a QR card encoding {@code otpauthUri} to {@code player}. Returns a future
     * that completes once the card item is in the player's hotbar.
     */
    public CompletableFuture<Void> deliver(Player player, String otpauthUri, PaperSession session) {
        // QR encoding is CPU-bound — do it on the async pool.
        return async.submit(() -> {
            var matrix = QrEncoder.encode(otpauthUri);
            return MapPaletteRasterizer.rasterize(matrix);
        }).thenAcceptAsync(grid -> {
            // Map allocation + inventory mutation MUST happen on the main / region thread.
            MapView view = Bukkit.createMap(player.getWorld());
            for (MapRenderer r : view.getRenderers()) view.removeRenderer(r);
            view.addRenderer(new QrMapRenderer(grid));
            session.setActiveMapId(java.util.Optional.of(view.getId()));

            ItemStack card = new ItemStack(Material.FILLED_MAP);
            card.editMeta(meta -> {
                if (meta instanceof org.bukkit.inventory.meta.MapMeta mm) {
                    mm.setMapView(view);
                    mm.displayName(net.kyori.adventure.text.Component.text(
                            "ObsidianAuth Setup Card",
                            net.kyori.adventure.text.format.NamedTextColor.AQUA));
                }
            });

            PlayerInventory inv = player.getInventory();
            int slot = pickSlot(inv);
            ItemStack displaced = inv.getItem(slot);
            if (displaced != null && displaced.getType() != Material.AIR) {
                // Persist the displaced item BEFORE we mutate the inventory.
                stash.save(player.getUniqueId(), slot, displaced).join();
                session.setStashedItemRef(java.util.Optional.of(player.getUniqueId()));
            }
            inv.setItem(slot, card);
            inv.setHeldItemSlot(slot);
        }, sync.asExecutor());
    }

    /**
     * Restore any stashed item to the player's inventory. Idempotent — no-op if no stash
     * exists. Called on auth success, disconnect, dismissal, or startup pre-join scan.
     */
    public CompletableFuture<Void> restoreStash(Player player) {
        return stash.load(player.getUniqueId()).thenAcceptAsync(maybeEntry -> {
            if (maybeEntry.isEmpty()) return;
            var entry = maybeEntry.get();
            PlayerInventory inv = player.getInventory();
            // Clear the QR card from the slot first.
            ItemStack current = inv.getItem(entry.slotIndex());
            if (current != null && current.getType() == Material.FILLED_MAP) {
                inv.setItem(entry.slotIndex(), entry.item());
            } else {
                // Slot was edited by the player somehow (shouldn't happen given the lockdown).
                // Restore the original item to the slot anyway — it's safer than dropping it.
                inv.setItem(entry.slotIndex(), entry.item());
            }
            stash.delete(player.getUniqueId()).join();
        }, sync.asExecutor());
    }

    private static int pickSlot(PlayerInventory inv) {
        // Prefer first empty hotbar slot (0..8).
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() == Material.AIR) {
                return i;
            }
        }
        // Otherwise borrow the currently-selected hotbar slot.
        return inv.getHeldItemSlot();
    }
}
