package org.alex_melan.obsidianauth.velocity.listeners;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import org.alex_melan.obsidianauth.core.channel.AuthState;
import org.alex_melan.obsidianauth.velocity.channel.VelocityGateService;

/**
 * Cancels chat at the proxy layer while the player is not {@code AUTHED} on their backend.
 *
 * <p>Returns an {@link EventTask} so the proxy's event loop is never blocked: Velocity
 * suspends event dispatch until the gate future resolves, then resumes. If the backend
 * doesn't answer within the timeout, the gate resolves to {@link AuthState#UNKNOWN} and
 * the chat is denied — failure-closed.
 *
 * <p>This is a defence-in-depth measure for the brief window where a player is connected
 * to the proxy but their backend session state is still settling. Paper remains the
 * authority; this listener never makes a player AUTHED, it only blocks.
 */
public final class ProxyChatListener {

    private final VelocityGateService gate;
    private final boolean enabled;

    public ProxyChatListener(VelocityGateService gate, boolean enabled) {
        this.gate = gate;
        this.enabled = enabled;
    }

    @Subscribe
    public EventTask onChat(PlayerChatEvent event) {
        if (!enabled || !event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.resumeWhenComplete(
                gate.requestGate(event.getPlayer()).thenAccept(state -> {
                    if (state != AuthState.AUTHED) {
                        event.setResult(PlayerChatEvent.ChatResult.denied());
                    }
                }));
    }
}
