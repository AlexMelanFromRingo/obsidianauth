package org.alex_melan.obsidianauth.paper.listeners;

import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.storage.EnrollmentDao;
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
 * On {@code PlayerJoinEvent}: spawns a {@link PaperSession} in {@code
 * PENDING_ENROLLMENT} (no record exists) or {@code LOCKED_AWAITING_CODE} (record exists),
 * then either starts the enrollment flow or sends the awaiting-code prompt.
 *
 * <p>On {@code PlayerQuitEvent}: restores any stashed inventory item and removes the
 * session.
 */
public final class JoinQuitListener implements Listener {

    @SuppressWarnings("unused")
    private final AsyncExecutor async;
    private final SessionRegistry sessions;
    private final EnrollmentDao dao;
    private final EnrollmentOrchestrator orchestrator;
    private final CardDeliveryService cardDelivery;

    public JoinQuitListener(AsyncExecutor async,
                            SessionRegistry sessions,
                            EnrollmentDao dao,
                            EnrollmentOrchestrator orchestrator,
                            CardDeliveryService cardDelivery) {
        this.async = async;
        this.sessions = sessions;
        this.dao = dao;
        this.orchestrator = orchestrator;
        this.cardDelivery = cardDelivery;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Pre-emptively register a PENDING session synchronously so listeners deny actions
        // BEFORE the async DAO lookup completes (failure-closed during the in-flight period).
        PaperSession session = new PaperSession(
                player.getUniqueId(),
                player.getLocation(),
                PaperSession.State.PENDING_ENROLLMENT);
        sessions.register(session);
        // Now look up whether they're already enrolled and start the appropriate flow.
        dao.findByPlayerUuid(player.getUniqueId()).thenAccept(maybe -> {
            if (maybe.isPresent()) {
                session.setState(PaperSession.State.LOCKED_AWAITING_CODE);
            }
            orchestrator.startEnrollment(player, session);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sessions.remove(player.getUniqueId());
        // Restore any stashed item BEFORE Paper saves the player file.
        cardDelivery.restoreStash(player);
    }
}
