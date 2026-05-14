package org.alex_melan.obsidianauth.paper.totp;

import static org.assertj.core.api.Assertions.assertThat;

import org.alex_melan.obsidianauth.core.totp.SecretGenerator;
import org.alex_melan.obsidianauth.core.totp.TotpGenerator;
import org.alex_melan.obsidianauth.core.totp.TotpVerifier;
import org.junit.jupiter.api.Test;

/**
 * US3: with {@code totp.window_steps = 2} the verifier tolerates ±2 thirty-second steps
 * (±60 s of clock skew) but rejects anything further out.
 */
class WindowToleranceIT {

    private static final int STEP_SECONDS = 30;
    private static final int WINDOW_STEPS = 2;
    private static final int DIGITS = 6;
    private static final TotpGenerator.Algorithm ALG = TotpGenerator.Algorithm.SHA1;
    /** A fixed "server now" so the test is independent of wall-clock. */
    private static final long NOW_SECONDS = 1_700_000_000L;

    private final byte[] secret = new SecretGenerator().generate();
    private final long centerCounter = TotpGenerator.counterFor(NOW_SECONDS, STEP_SECONDS);

    private TotpVerifier.Outcome verifyCodeFromCounter(long counter) {
        String code = TotpGenerator.generate(secret, counter, DIGITS, ALG);
        return TotpVerifier.verify(
                code, secret, NOW_SECONDS, STEP_SECONDS, WINDOW_STEPS, DIGITS, ALG, null).outcome();
    }

    @Test
    void codeFromSixtySecondsAgo_isAccepted() {
        // -60 s == two steps back == the far edge of the ±2 window.
        assertThat(verifyCodeFromCounter(centerCounter - 2))
                .isEqualTo(TotpVerifier.Outcome.OK_VERIFIED);
    }

    @Test
    void codeFromSixtySecondsAhead_isAccepted() {
        assertThat(verifyCodeFromCounter(centerCounter + 2))
                .isEqualTo(TotpVerifier.Outcome.OK_VERIFIED);
    }

    @Test
    void codeFromNinetySecondsAgo_isRejected() {
        // -90 s == three steps back == outside the ±2 window.
        assertThat(verifyCodeFromCounter(centerCounter - 3))
                .isEqualTo(TotpVerifier.Outcome.WRONG_CODE);
    }
}
