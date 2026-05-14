package org.alex_melan.obsidianauth.paper.listeners;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.alex_melan.obsidianauth.paper.enrollment.CardDeliveryService;
import org.alex_melan.obsidianauth.paper.enrollment.EnrollmentOrchestrator;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * On {@code PlayerJoinEvent}: registers a {@link PaperSession} synchronously (so the
 * lockdown listeners deny every action from the first tick — failure-closed) and hands off
 * to {@link EnrollmentOrchestrator}, which performs the single enrollment lookup and drives
 * the session into {@code LOCKED_AWAITING_CODE} (returning player) or through fresh
 * enrollment.
 *
 * <p>On {@code PlayerQuitEvent}: synchronously restores any borrowed item and removes the
 * QR card before Paper writes the player's data file, then discards the session.
 */
public final class JoinQuitListener implements Listener {

    private final SessionRegistry sessions;
    private final EnrollmentOrchestrator orchestrator;
    private final CardDeliveryService cardDelivery;
    private final Logger log;

    public JoinQuitListener(SessionRegistry sessions,
                            EnrollmentOrchestrator orchestrator,
                            CardDeliveryService cardDelivery,
                            Logger log) {
        this.sessions = sessions;
        this.orchestrator = orchestrator;
        this.cardDelivery = cardDelivery;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Register a PENDING session synchronously so the lockdown listeners deny every
        // action BEFORE the async enrollment lookup completes (failure-closed in-flight).
        PaperSession session = new PaperSession(
                player.getUniqueId(),
                player.getLocation(),
                PaperSession.State.PENDING_ENROLLMENT);
        sessions.register(session);
        // The orchestrator owns the single enrollment lookup and the resulting state
        // transition (LOCKED_AWAITING_CODE for a returning player, fresh enrollment
        // otherwise). Any failure is logged — never silently swallowed — and the player
        // stays locked (failure-closed).
        orchestrator.startEnrollment(player, session).exceptionally(err -> {
            log.log(Level.WARNING, "enrollment flow failed for " + player.getUniqueId()
                    + " — player remains locked", err);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PaperSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            // Synchronous: the inventory must be restored before Paper saves the data file.
            cardDelivery.dismissCardOnQuit(player, session);
        }
    }
}
