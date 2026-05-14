package org.alex_melan.obsidianauth.paper.session;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;

/**
 * Per-player runtime state. Lives in {@link SessionRegistry}'s {@link
 * java.util.concurrent.ConcurrentHashMap}; constructed on {@code PlayerJoinEvent},
 * discarded on {@code PlayerQuitEvent}.
 *
 * <p>Every field a listener reads from the main / region thread is {@code volatile} or an
 * {@link java.util.concurrent.atomic atomic} reference. The {@code state} field is the
 * load-bearing one — listeners cancel events based on its value WITHOUT taking any lock.
 */
public final class PaperSession {

    public enum State {
        /** Joined without an enrollment record — the QR card flow has not finished. */
        PENDING_ENROLLMENT,
        /** Has a stored secret; awaiting a valid code. */
        LOCKED_AWAITING_CODE,
        /** Code verified for this session. Plays normally. */
        AUTHED,
        /** Rate limiter tripped. Will be kicked. */
        LOCKED_OUT
    }

    private final UUID playerUuid;
    private final Location freezeAnchor;

    private volatile State state;
    private volatile Optional<UUID> stashedItemRef = Optional.empty();
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastAttemptAtMillis = 0L;
    private volatile Optional<Integer> activeMapId = Optional.empty();
    private volatile boolean pendingVerification = false;
    private volatile Long pendingSecretRefreshKey = null;

    public PaperSession(UUID playerUuid, Location freezeAnchor, State initialState) {
        this.playerUuid = playerUuid;
        this.freezeAnchor = freezeAnchor;
        this.state = initialState;
    }

    public UUID playerUuid() { return playerUuid; }
    public Location freezeAnchor() { return freezeAnchor; }
    public State state() { return state; }
    public void setState(State s) { this.state = s; }

    public Optional<UUID> stashedItemRef() { return stashedItemRef; }
    public void setStashedItemRef(Optional<UUID> ref) { this.stashedItemRef = ref; }

    public int failureCount() { return failureCount.get(); }
    public int incrementFailure() { return failureCount.incrementAndGet(); }
    public void resetFailures() { failureCount.set(0); }

    public long lastAttemptAtMillis() { return lastAttemptAtMillis; }
    public void setLastAttemptAtMillis(long t) { this.lastAttemptAtMillis = t; }

    public Optional<Integer> activeMapId() { return activeMapId; }
    public void setActiveMapId(Optional<Integer> id) { this.activeMapId = id; }

    public boolean pendingVerification() { return pendingVerification; }
    public void setPendingVerification(boolean v) { this.pendingVerification = v; }

    public Long pendingSecretRefreshKey() { return pendingSecretRefreshKey; }
    public void setPendingSecretRefreshKey(Long v) { this.pendingSecretRefreshKey = v; }
}
