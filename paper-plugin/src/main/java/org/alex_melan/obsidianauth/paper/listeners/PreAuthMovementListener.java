package org.alex_melan.obsidianauth.paper.listeners;

import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

/**
 * Cancels movement beyond a small tolerance from the freeze anchor while the player is
 * not {@code AUTHED}. Implements the movement portion of FR-006.
 */
public final class PreAuthMovementListener implements Listener {

    /** Max delta from freeze anchor before we snap the player back. */
    private static final double TOLERANCE_BLOCKS_SQ = 3.0 * 3.0;

    private final SessionRegistry sessions;

    public PreAuthMovementListener(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        PaperSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() == PaperSession.State.AUTHED) return;

        Location to = event.getTo();
        if (to == null) return;
        Location anchor = session.freezeAnchor();
        if (!to.getWorld().equals(anchor.getWorld())
                || to.distanceSquared(anchor) > TOLERANCE_BLOCKS_SQ) {
            event.setTo(anchor.clone());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        PaperSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() == PaperSession.State.AUTHED) return;
        // Allow our own plugin-initiated teleports (PLUGIN cause) — they're the freeze-snap-back.
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        PaperSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() == PaperSession.State.AUTHED) return;
        event.setCancelled(true);
    }
}
