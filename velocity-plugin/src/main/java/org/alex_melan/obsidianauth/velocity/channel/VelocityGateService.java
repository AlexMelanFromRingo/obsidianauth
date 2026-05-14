package org.alex_melan.obsidianauth.velocity.channel;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.channel.AuthState;
import org.alex_melan.obsidianauth.core.channel.ChannelCodec;
import org.alex_melan.obsidianauth.core.channel.ChannelId;
import org.alex_melan.obsidianauth.core.channel.ChannelMessage;
import org.alex_melan.obsidianauth.core.channel.MessageType;
import org.alex_melan.obsidianauth.velocity.session.VelocitySession;
import org.alex_melan.obsidianauth.velocity.session.VelocitySessionRegistry;
import org.slf4j.Logger;

/**
 * Sends {@code GATE_REQUEST} frames to the player's current backend and correlates the
 * {@code GATE_RESPONSE} back into a {@link CompletableFuture}.
 *
 * <p>Concurrent chat / command events for the same player coalesce onto a single in-flight
 * request — the second event awaits the same future the first one created.
 *
 * <p>Failure-closed: if the backend doesn't answer within the configured timeout, or the
 * player isn't connected to any backend, the future completes with {@link AuthState#UNKNOWN}
 * and callers treat {@code UNKNOWN} as "cancel the event".
 */
public final class VelocityGateService {

    private final ProxyServer proxy;
    private final Object plugin;
    private final ChannelCodec codec;
    private final byte[] hmacSecret;
    private final AsyncExecutor async;
    private final VelocitySessionRegistry registry;
    private final long timeoutMillis;
    private final Logger log;
    private final MinecraftChannelIdentifier channelId = MinecraftChannelIdentifier.from(ChannelId.ID);

    public VelocityGateService(ProxyServer proxy, Object plugin, ChannelCodec codec,
                               byte[] hmacSecret, AsyncExecutor async,
                               VelocitySessionRegistry registry, long timeoutMillis, Logger log) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.codec = codec;
        this.hmacSecret = hmacSecret;
        this.async = async;
        this.registry = registry;
        this.timeoutMillis = timeoutMillis;
        this.log = log;
    }

    /**
     * Resolve the player's auth state. Uses the cached value if it's still fresh; otherwise
     * sends a {@code GATE_REQUEST} and returns a future that completes when the
     * {@code GATE_RESPONSE} arrives (or the timeout fires).
     */
    public CompletableFuture<AuthState> requestGate(Player player) {
        UUID uuid = player.getUniqueId();
        VelocitySession session = registry.getOrCreate(uuid);
        long now = System.currentTimeMillis();

        if (session.isFresh(now, timeoutMillis)) {
            return CompletableFuture.completedFuture(session.lastKnownState());
        }
        // Coalesce onto an existing in-flight request.
        CompletableFuture<AuthState> existing = session.inflightGateRequest();
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        CompletableFuture<AuthState> future = new CompletableFuture<>();
        session.setInflightGateRequest(future);

        Optional<ServerConnection> server = player.getCurrentServer();
        if (server.isEmpty()) {
            // No backend — failure-closed.
            future.complete(AuthState.UNKNOWN);
            session.clearInflightGateRequest();
            return future;
        }

        // Encode + sign the GATE_REQUEST on the async pool, then send.
        ChannelMessage request = new ChannelMessage(
                MessageType.GATE_REQUEST, uuid, codec.freshNonce(), codec.now(), new byte[0]);
        codec.encodeAsync(request, hmacSecret).whenComplete((frame, err) -> {
            if (err != null) {
                log.warn("failed to encode GATE_REQUEST for {}: {}", uuid, err.getMessage());
                completeIfPending(session, future, AuthState.UNKNOWN);
                return;
            }
            boolean sent = server.get().sendPluginMessage(channelId, frame);
            if (!sent) {
                completeIfPending(session, future, AuthState.UNKNOWN);
            }
        });

        // Failure-closed timeout.
        proxy.getScheduler().buildTask(plugin, () -> completeIfPending(session, future, AuthState.UNKNOWN))
                .delay(timeoutMillis, TimeUnit.MILLISECONDS)
                .schedule();

        return future;
    }

    /** Called by {@code VelocityChannelHandler} when a {@code GATE_RESPONSE} arrives. */
    public void onGateResponse(UUID playerUuid, AuthState state, byte[] token) {
        VelocitySession session = registry.getOrCreate(playerUuid);
        session.recordResponse(state, token, System.currentTimeMillis());
        CompletableFuture<AuthState> pending = session.inflightGateRequest();
        if (pending != null && !pending.isDone()) {
            pending.complete(state);
        }
        session.clearInflightGateRequest();
    }

    /** Called by {@code VelocityChannelHandler} when an {@code INVALIDATE} arrives. */
    public void onInvalidate(UUID playerUuid) {
        VelocitySession session = registry.get(playerUuid);
        if (session != null) {
            session.invalidate();
            CompletableFuture<AuthState> pending = session.inflightGateRequest();
            if (pending != null && !pending.isDone()) {
                pending.complete(AuthState.UNKNOWN);
            }
            session.clearInflightGateRequest();
        }
    }

    private static void completeIfPending(VelocitySession session,
                                          CompletableFuture<AuthState> future,
                                          AuthState fallback) {
        if (!future.isDone()) {
            future.complete(fallback);
        }
        session.clearInflightGateRequest();
    }
}
