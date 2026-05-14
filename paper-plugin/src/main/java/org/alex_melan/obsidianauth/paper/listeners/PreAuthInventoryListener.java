package org.alex_melan.obsidianauth.paper.listeners;

import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Cancels inventory mutations (drop, pickup, click) and container open while the player
 * is not {@code AUTHED}.
 */
public final class PreAuthInventoryListener implements Listener {

    private final SessionRegistry sessions;

    public PreAuthInventoryListener(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && isLocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && isLocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p && isLocked(p)) {
            event.setCancelled(true);
        }
    }

    private boolean isLocked(Player p) {
        PaperSession s = sessions.get(p.getUniqueId());
        return s != null && s.state() != PaperSession.State.AUTHED;
    }
}
