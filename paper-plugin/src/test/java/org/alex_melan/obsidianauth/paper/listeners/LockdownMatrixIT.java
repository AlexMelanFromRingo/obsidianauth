package org.alex_melan.obsidianauth.paper.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.TestConfigs;
import org.alex_melan.obsidianauth.paper.config.LiveConfig;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.alex_melan.obsidianauth.paper.verification.ChatVerificationService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the FR-006 / FR-007 lockdown matrix.
 *
 * <p>One pair of test methods per cancellable event class enumerated in FR-007. For each
 * class both halves of the constitutional requirement are asserted: the event is cancelled
 * while {@code state != AUTHED}, and proceeds untouched while {@code state == AUTHED}.
 *
 * <p>Events with awkward public constructors (BlockPlace, Interact, EntityDamage,
 * InventoryClick, DropItem, EntityPickup, AsyncChat) are exercised through Mockito stubs —
 * the listeners only ever read a handful of accessors and call {@code setCancelled}, so a
 * stub is a faithful stand-in and avoids depending on deprecated constructor overloads.
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

    // --- Block place ---

    @Test
    void blockPlace_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onBlockPlace(event);

        verify(event).setCancelled(true);
    }

    @Test
    void blockPlace_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onBlockPlace(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // --- Interact ---

    @Test
    void interact_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(null);
        listener.onInteract(event);

        verify(event).setCancelled(true);
    }

    @Test
    void interact_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onInteract(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // --- Entity damage (player as the damager) ---

    @Test
    void entityDamage_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(player);
        listener.onEntityDamage(event);

        verify(event).setCancelled(true);
    }

    @Test
    void entityDamage_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInteractionListener listener = new PreAuthInteractionListener(registry);

        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(player);
        listener.onEntityDamage(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // --- Inventory click ---

    @Test
    void inventoryClick_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInventoryListener listener = new PreAuthInventoryListener(registry);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        listener.onClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void inventoryClick_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInventoryListener listener = new PreAuthInventoryListener(registry);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        listener.onClick(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // --- Drop item ---

    @Test
    void dropItem_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInventoryListener listener = new PreAuthInventoryListener(registry);

        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onDrop(event);

        verify(event).setCancelled(true);
    }

    @Test
    void dropItem_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInventoryListener listener = new PreAuthInventoryListener(registry);

        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onDrop(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // --- Entity pickup ---

    @Test
    void entityPickup_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.LOCKED_AWAITING_CODE);
        PreAuthInventoryListener listener = new PreAuthInventoryListener(registry);

        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getEntity()).thenReturn(player);
        listener.onPickup(event);

        verify(event).setCancelled(true);
    }

    @Test
    void entityPickup_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        PreAuthInventoryListener listener = new PreAuthInventoryListener(registry);

        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getEntity()).thenReturn(player);
        listener.onPickup(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    // --- Async chat ---

    @Test
    void asyncChat_cancelledWhenLocked() {
        setUpPlayer(PaperSession.State.PENDING_ENROLLMENT);
        LiveConfig config = new LiveConfig(TestConfigs.totpDefaults());
        ChatVerificationService verification = mock(ChatVerificationService.class);
        PreAuthChatListener listener = new PreAuthChatListener(registry, verification, config);

        AsyncChatEvent event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onChat(event);

        verify(event).setCancelled(true);
    }

    @Test
    void asyncChat_allowedWhenAuthed() {
        setUpPlayer(PaperSession.State.AUTHED);
        LiveConfig config = new LiveConfig(TestConfigs.totpDefaults());
        ChatVerificationService verification = mock(ChatVerificationService.class);
        PreAuthChatListener listener = new PreAuthChatListener(registry, verification, config);

        AsyncChatEvent event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onChat(event);

        verify(event, never()).setCancelled(anyBoolean());
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
