package org.alex_melan.obsidianauth.core.ratelimit;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter for TOTP verification attempts.
 *
 * <p>Independently tracks failures per Minecraft account (UUID) and per source IP. Both
 * dimensions are checked at submission time; either tripping the threshold returns
 * {@link Outcome#LOCKED_OUT}. The limiter does NOT itself kick players — the listener
 * that consumes the outcome is responsible for that.
 *
 * <p>This class is safe to read from the main / region thread (lock-free
 * {@link ConcurrentHashMap} lookups, plus a fine-grained {@code synchronized} block per
 * key while we trim and count). It does no I/O.
 *
 * <p>Memory is bounded by the entry-eviction sweep: any key whose deque is empty is
 * removed on the next access. For populations of hundreds of online players the
 * footprint is a few KB.
 */
public final class AttemptLimiter {

    public enum Outcome {
        /** Attempt counted; the caller may proceed to verify. */
        OK,
        /** Threshold exceeded for either the account or the IP; reject without verifying. */
        LOCKED_OUT
    }

    /** Result of {@link #recordFailure}, exposed so the caller can decide whether to kick. */
    public record FailureSnapshot(Outcome outcome, int accountFailures, int ipFailures) {}

    private final int maxFailuresPerWindow;
    private final long windowMillis;
    private final java.util.function.LongSupplier clock;

    private final ConcurrentHashMap<UUID,   Deque<Long>> byAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IpKey,  Deque<Long>> byIp      = new ConcurrentHashMap<>();

    public AttemptLimiter(int maxFailuresPerWindow, int windowSeconds) {
        this(maxFailuresPerWindow, windowSeconds, System::currentTimeMillis);
    }

    /** Visible for testing — production code uses the no-clock constructor. */
    AttemptLimiter(int maxFailuresPerWindow, int windowSeconds, java.util.function.LongSupplier clock) {
        if (maxFailuresPerWindow < 1) {
            throw new IllegalArgumentException("maxFailuresPerWindow MUST be >= 1");
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds MUST be >= 1");
        }
        this.maxFailuresPerWindow = maxFailuresPerWindow;
        this.windowMillis = windowSeconds * 1000L;
        this.clock = clock;
    }

    /**
     * Check both dimensions WITHOUT recording an attempt. Returns {@link Outcome#LOCKED_OUT}
     * if either is over threshold. Used by the chat listener to short-circuit before doing
     * any DB / crypto work for an already-locked-out player.
     */
    public Outcome check(UUID playerUuid, byte[] sourceIp) {
        long now = clock.getAsLong();
        return (countWithin(byAccount, playerUuid, now) >= maxFailuresPerWindow
                || countWithin(byIp, new IpKey(sourceIp), now) >= maxFailuresPerWindow)
                ? Outcome.LOCKED_OUT
                : Outcome.OK;
    }

    /** Record a failed verification. Returns the post-record snapshot. */
    public FailureSnapshot recordFailure(UUID playerUuid, byte[] sourceIp) {
        long now = clock.getAsLong();
        int accountFailures = recordAndCount(byAccount, playerUuid, now);
        int ipFailures      = recordAndCount(byIp, new IpKey(sourceIp), now);
        Outcome outcome = (accountFailures >= maxFailuresPerWindow || ipFailures >= maxFailuresPerWindow)
                ? Outcome.LOCKED_OUT
                : Outcome.OK;
        return new FailureSnapshot(outcome, accountFailures, ipFailures);
    }

    /**
     * Forget all recorded failures for a player. Called after a successful verification,
     * since accumulated failures shouldn't kick the player on next login.
     */
    public void reset(UUID playerUuid) {
        byAccount.remove(playerUuid);
    }

    // --- helpers ---

    private <K> int countWithin(ConcurrentHashMap<K, Deque<Long>> map, K key, long now) {
        Deque<Long> deque = map.get(key);
        if (deque == null) return 0;
        synchronized (deque) {
            evictExpired(deque, now);
            int size = deque.size();
            if (size == 0) {
                map.remove(key, deque);
            }
            return size;
        }
    }

    private <K> int recordAndCount(ConcurrentHashMap<K, Deque<Long>> map, K key, long now) {
        Deque<Long> deque = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            evictExpired(deque, now);
            deque.addLast(now);
            return deque.size();
        }
    }

    private void evictExpired(Deque<Long> deque, long now) {
        long threshold = now - windowMillis;
        while (true) {
            Long head = deque.peekFirst();
            if (head == null || head >= threshold) return;
            deque.pollFirst();
        }
    }

    /** Wrapper so {@code byte[]} keys hash / equate by content rather than identity. */
    private record IpKey(byte[] bytes) {
        @Override public boolean equals(Object o) {
            return o instanceof IpKey k && Arrays.equals(this.bytes, k.bytes);
        }
        @Override public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
