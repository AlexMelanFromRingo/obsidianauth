package org.alex_melan.obsidianauth.velocity.session;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.alex_melan.obsidianauth.core.channel.AuthState;

/**
 * Proxy-side mirror of a player's auth state. Velocity holds NO secret and NO database
 * connection — this is purely a cache of what Paper last told us, plus an in-flight
 * gate-request future so concurrent chat/command events for the same player coalesce
 * onto a single round-trip.
 *
 * <p>All fields are {@code volatile} — Velocity has no main thread, so reads and writes
 * happen across the proxy's event-loop and worker threads.
 */
public final class VelocitySession {

    private final UUID playerUuid;

    private volatile AuthState lastKnownState = AuthState.UNKNOWN;
    private volatile long lastResponseAtMillis = 0L;
    private volatile byte[] opaqueSessionToken = new byte[0];
    private volatile CompletableFuture<AuthState> inflightGateRequest = null;

    public VelocitySession(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID playerUuid() { return playerUuid; }

    public AuthState lastKnownState() { return lastKnownState; }
    public long lastResponseAtMillis() { return lastResponseAtMillis; }
    public byte[] opaqueSessionToken() { return opaqueSessionToken; }
    public CompletableFuture<AuthState> inflightGateRequest() { return inflightGateRequest; }

    public void recordResponse(AuthState state, byte[] token, long nowMillis) {
        this.lastKnownState = state;
        this.opaqueSessionToken = token;
        this.lastResponseAtMillis = nowMillis;
    }

    public void setInflightGateRequest(CompletableFuture<AuthState> future) {
        this.inflightGateRequest = future;
    }

    public void clearInflightGateRequest() {
        this.inflightGateRequest = null;
    }

    /** Invalidate the cache — next event must re-ask Paper. */
    public void invalidate() {
        this.lastKnownState = AuthState.UNKNOWN;
        this.opaqueSessionToken = new byte[0];
        this.lastResponseAtMillis = 0L;
    }

    /** True if the cached state is still within the freshness window. */
    public boolean isFresh(long nowMillis, long freshnessWindowMillis) {
        return lastKnownState != AuthState.UNKNOWN
                && (nowMillis - lastResponseAtMillis) <= freshnessWindowMillis;
    }
}
