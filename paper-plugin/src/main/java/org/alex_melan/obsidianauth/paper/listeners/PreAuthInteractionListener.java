package org.alex_melan.obsidianauth.paper.listeners;

import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Cancels world interaction (block break, block place, entity damage, entity interact)
 * while the player is not {@code AUTHED}. The QR card item ({@code FILLED_MAP}) IS
 * allowed to be right-clicked so the player can view the QR — that's the sole permitted
 * interaction per FR-009.
 */
public final class PreAuthInteractionListener implements Listener {

    private final SessionRegistry sessions;

    public PreAuthInteractionListener(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!isLocked(event.getPlayer())) return;
        // Allow right-clicking the QR card item (only).
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.FILLED_MAP) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isLocked(p)) {
            event.setCancelled(true);
        }
    }

    private boolean isLocked(Player p) {
        PaperSession s = sessions.get(p.getUniqueId());
        return s != null && s.state() != PaperSession.State.AUTHED;
    }
}
