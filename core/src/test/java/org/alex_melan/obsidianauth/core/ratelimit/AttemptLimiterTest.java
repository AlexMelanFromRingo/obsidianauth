package org.alex_melan.obsidianauth.core.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private AttemptLimiter limiter(int maxFailures, int windowSeconds) {
        return new AttemptLimiter(maxFailures, windowSeconds, clock::get);
    }

    private static byte[] ipv4(int a, int b, int c, int d) {
        return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d};
    }

    @Test
    void firstFailuresUnderThreshold_returnOk() {
        AttemptLimiter rl = limiter(3, 60);
        UUID uuid = UUID.randomUUID();
        byte[] ip = ipv4(10, 0, 0, 1);
        assertThat(rl.recordFailure(uuid, ip).outcome()).isEqualTo(AttemptLimiter.Outcome.OK);
        assertThat(rl.recordFailure(uuid, ip).outcome()).isEqualTo(AttemptLimiter.Outcome.OK);
        assertThat(rl.recordFailure(uuid, ip).outcome()).isEqualTo(AttemptLimiter.Outcome.LOCKED_OUT);
    }

    @Test
    void perAccountAndPerIp_areIndependent() {
        AttemptLimiter rl = limiter(3, 60);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        byte[] sharedIp = ipv4(10, 0, 0, 1);
        byte[] bobsIp = ipv4(10, 0, 0, 2);

        // Alice fails twice from the shared IP. Both per-account and per-IP counts are below threshold.
        var alice1 = rl.recordFailure(alice, sharedIp);
        var alice2 = rl.recordFailure(alice, sharedIp);
        assertThat(alice1.outcome()).isEqualTo(AttemptLimiter.Outcome.OK);
        assertThat(alice2.outcome()).isEqualTo(AttemptLimiter.Outcome.OK);

        // Bob fails twice from a DIFFERENT IP. His account is below threshold; both IPs are below threshold.
        var bob1 = rl.recordFailure(bob, bobsIp);
        var bob2 = rl.recordFailure(bob, bobsIp);
        assertThat(bob1.outcome()).isEqualTo(AttemptLimiter.Outcome.OK);
        assertThat(bob2.outcome()).isEqualTo(AttemptLimiter.Outcome.OK);

        // But if Bob now fails from Alice's IP, the IP count is at 3 and we lock out.
        var bobOnSharedIp = rl.recordFailure(bob, sharedIp);
        assertThat(bobOnSharedIp.outcome()).isEqualTo(AttemptLimiter.Outcome.LOCKED_OUT);
        assertThat(bobOnSharedIp.ipFailures()).isEqualTo(3);
        assertThat(bobOnSharedIp.accountFailures()).isEqualTo(3);
    }

    @Test
    void windowRollover_resetsCount() {
        AttemptLimiter rl = limiter(3, 60);
        UUID uuid = UUID.randomUUID();
        byte[] ip = ipv4(10, 0, 0, 1);

        rl.recordFailure(uuid, ip);
        rl.recordFailure(uuid, ip);
        rl.recordFailure(uuid, ip);
        assertThat(rl.check(uuid, ip)).isEqualTo(AttemptLimiter.Outcome.LOCKED_OUT);

        // Advance the clock past the window.
        clock.addAndGet(120_000);
        assertThat(rl.check(uuid, ip)).isEqualTo(AttemptLimiter.Outcome.OK);
    }

    @Test
    void reset_clearsAccountAttemptsOnly() {
        AttemptLimiter rl = limiter(3, 60);
        UUID uuid = UUID.randomUUID();
        byte[] ip = ipv4(10, 0, 0, 1);

        rl.recordFailure(uuid, ip);
        rl.recordFailure(uuid, ip);
        rl.reset(uuid);
        assertThat(rl.check(uuid, ip)).isEqualTo(AttemptLimiter.Outcome.OK);
    }

    @Test
    void check_doesNotRecord() {
        AttemptLimiter rl = limiter(1, 60);
        UUID uuid = UUID.randomUUID();
        byte[] ip = ipv4(10, 0, 0, 1);

        assertThat(rl.check(uuid, ip)).isEqualTo(AttemptLimiter.Outcome.OK);
        assertThat(rl.check(uuid, ip)).isEqualTo(AttemptLimiter.Outcome.OK);
        rl.recordFailure(uuid, ip);   // now we're at the threshold
        assertThat(rl.check(uuid, ip)).isEqualTo(AttemptLimiter.Outcome.LOCKED_OUT);
    }
}
