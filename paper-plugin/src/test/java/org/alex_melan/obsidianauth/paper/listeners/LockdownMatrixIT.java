package org.alex_melan.obsidianauth.paper.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the FR-006 / FR-007 lockdown matrix.
 *
 * <p>For each event class, asserts BOTH halves of the constitutional requirement:
 * "blocked when unauthenticated, allowed when authenticated".
 */
class LockdownMatrixIT extends IntegrationTestBase {

    private SessionRegistry registry;
    private PlayerMock player;
    private Location anchor;

    private void setUpPlayer(PaperSession.State state) {
        registry = new SessionRegistry();
        player = server.addPlayer();
        anchor = player.getLocation().clone();
        registry.register(new PaperSession(player.getUniqueId(), anchor, state));
    }

    // --- Block break ---

    @Test
    void blockBreak_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        Block block = anchor.getBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    void blockBreak_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        Block block = anchor.getBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }

    // --- Command ---

    @Test
    void command_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthCommandListener listener = new PreAuthCommandListener(registry);

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/spawn");
        listener.onCommand(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    void command_allowlistedCommandNotCancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthCommandListener listener = new PreAuthCommandListener(registry);

        PlayerCommandPreprocessEvent helpEvent = new PlayerCommandPreprocessEvent(player, "/help");
        listener.onCommand(helpEvent);
        assertThat(helpEvent.isCancelled()).isFalse();

        PlayerCommandPreprocessEvent totpEvent = new PlayerCommandPreprocessEvent(player, "/totp 123456");
        listener.onCommand(totpEvent);
        assertThat(totpEvent.isCancelled()).isFalse();
    }

    @Test
    void command_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthCommandListener listener = new PreAuthCommandListener(registry);

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/spawn");
        listener.onCommand(event);

        assertThat(event.isCancelled()).isFalse();
    }

    // --- Movement ---

    @Test
    void movement_snappedBackWhenLockedAndMovingFar() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthMovementListener listener = new PreAuthMovementListener(registry);

        Location farAway = anchor.clone().add(50, 0, 50);
        PlayerMoveEvent event = new PlayerMoveEvent(player, anchor.clone(), farAway);
        listener.onMove(event);

        // The listener resets `to` to the anchor.
        assertThat(event.getTo().distanceSquared(anchor)).isLessThan(0.001);
    }

    @Test
    void movement_smallMoveWithinToleranceAllowedWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthMovementListener listener = new PreAuthMovementListener(registry);

        Location nearby = anchor.clone().add(1, 0, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player, anchor.clone(), nearby);
        listener.onMove(event);

        // Within tolerance — `to` is left untouched.
        assertThat(event.getTo().distanceSquared(nearby)).isLessThan(0.001);
    }

    @Test
    void movement_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthMovementListener listener = new PreAuthMovementListener(registry);

        Location farAway = anchor.clone().add(50, 0, 50);
        PlayerMoveEvent event = new PlayerMoveEvent(player, anchor.clone(), farAway);
        listener.onMove(event);

        assertThat(event.getTo().distanceSquared(farAway)).isLessThan(0.001);
    }

    // --- No session at all (player not tracked) ---

    @Test
    void noSession_eventNotTouched() {
        registry = new SessionRegistry();
        player = server.addPlayer();
        // No session registered for this player.
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        Block block = player.getLocation().getBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
