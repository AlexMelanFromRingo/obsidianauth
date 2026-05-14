package org.alex_melan.obsidianauth.velocity.listeners;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import org.alex_melan.obsidianauth.core.channel.AuthState;
import org.alex_melan.obsidianauth.velocity.channel.VelocityGateService;

/**
 * Cancels commands at the proxy layer while the player is not {@code AUTHED}.
 *
 * <p>Same {@link EventTask} non-blocking pattern as {@link ProxyChatListener}. Non-player
 * command sources (console) are never gated.
 */
public final class ProxyCommandListener {

    private final VelocityGateService gate;
    private final boolean enabled;

    public ProxyCommandListener(VelocityGateService gate, boolean enabled) {
        this.gate = gate;
        this.enabled = enabled;
    }

    @Subscribe
    public EventTask onCommand(CommandExecuteEvent event) {
        if (!enabled || !event.getResult().isAllowed()) {
            return null;
        }
        if (!(event.getCommandSource() instanceof Player player)) {
            return null;
        }
        return EventTask.resumeWhenComplete(
                gate.requestGate(player).thenAccept(state -> {
                    if (state != AuthState.AUTHED) {
                        event.setResult(CommandExecuteEvent.CommandResult.denied());
                    }
                }));
    }
}
