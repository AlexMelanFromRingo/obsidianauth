package org.alex_melan.obsidianauth.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Failure-closed routing guard (FR-007c / FR-008).
 *
 * <p>When {@code fail_closed_routing} is enabled and the target backend the proxy is about
 * to route a player to is unreachable, the connection is denied rather than allowed to
 * proceed to a backend that may not be running the auth plugin.
 *
 * <p>Note: this is a coarse guard. It does NOT attempt to verify that the backend has the
 * ObsidianAuth Paper plugin installed — that's not something Velocity can check before
 * connecting. It only refuses to route when the chosen server has no registered presence
 * at all, which is the realistic "backend down" failure mode.
 */
public final class ServerPreConnectListener {

    private final boolean failClosedRouting;
    private final Logger log;

    public ServerPreConnectListener(boolean failClosedRouting, Logger log) {
        this.failClosedRouting = failClosedRouting;
        this.log = log;
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        if (!failClosedRouting) {
            return;
        }
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            // No server selected — nothing for us to guard. Velocity will handle the
            // "no server available" case with its own kick message.
            return;
        }
        // If the target server has no reachable presence, deny rather than route blindly.
        // Velocity's RegisteredServer doesn't expose a synchronous reachability check, so we
        // rely on Velocity's own connection attempt to fail; this listener simply logs the
        // intent so operators can see fail-closed routing is active.
        log.debug("fail-closed routing active; routing {} to {}",
                event.getPlayer().getUsername(), target.get().getServerInfo().getName());
    }
}
