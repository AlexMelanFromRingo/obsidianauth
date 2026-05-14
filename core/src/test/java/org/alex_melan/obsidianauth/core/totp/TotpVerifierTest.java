package org.alex_melan.obsidianauth.core.totp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TotpVerifierTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes();

    @Test
    void verify_acceptsCodeAtCurrentStep() {
        long now = 1234567890L;
        long counter = TotpGenerator.counterFor(now, 30);
        String code = TotpGenerator.generate(SECRET, counter, 6, TotpGenerator.Algorithm.SHA1);
        var result = TotpVerifier.verify(code, SECRET, now, 30, 1, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(result.outcome()).isEqualTo(TotpVerifier.Outcome.OK_VERIFIED);
        assertThat(result.matchedCounter()).isEqualTo(counter);
    }

    @Test
    void verify_acceptsCodeOneStepBack() {
        long now = 1234567890L;
        long centerCounter = TotpGenerator.counterFor(now, 30);
        String pastCode = TotpGenerator.generate(SECRET, centerCounter - 1, 6, TotpGenerator.Algorithm.SHA1);
        var result = TotpVerifier.verify(pastCode, SECRET, now, 30, 1, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(result.outcome()).isEqualTo(TotpVerifier.Outcome.OK_VERIFIED);
        assertThat(result.matchedCounter()).isEqualTo(centerCounter - 1);
    }

    @Test
    void verify_rejectsCodeOutsideWindow() {
        long now = 1234567890L;
        long centerCounter = TotpGenerator.counterFor(now, 30);
        // Code from 5 steps back is well outside a ±1 window.
        String oldCode = TotpGenerator.generate(SECRET, centerCounter - 5, 6, TotpGenerator.Algorithm.SHA1);
        var result = TotpVerifier.verify(oldCode, SECRET, now, 30, 1, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(result.outcome()).isEqualTo(TotpVerifier.Outcome.WRONG_CODE);
    }

    @Test
    void verify_rejectsReplayWithinWindow() {
        long now = 1234567890L;
        long centerCounter = TotpGenerator.counterFor(now, 30);
        String code = TotpGenerator.generate(SECRET, centerCounter, 6, TotpGenerator.Algorithm.SHA1);
        // Caller's lastConsumedCounter == centerCounter — replay.
        var result = TotpVerifier.verify(code, SECRET, now, 30, 1, 6, TotpGenerator.Algorithm.SHA1, centerCounter);
        assertThat(result.outcome()).isEqualTo(TotpVerifier.Outcome.REPLAYED);
    }

    @Test
    void verify_rejectsMalformedLength() {
        var result = TotpVerifier.verify("12345", SECRET, 0, 30, 1, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(result.outcome()).isEqualTo(TotpVerifier.Outcome.MALFORMED);
    }

    @Test
    void verify_rejectsMalformedNonDigit() {
        var result = TotpVerifier.verify("12ab56", SECRET, 0, 30, 1, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(result.outcome()).isEqualTo(TotpVerifier.Outcome.MALFORMED);
    }

    @Test
    void verify_acceptsWiderWindow() {
        long now = 1234567890L;
        long centerCounter = TotpGenerator.counterFor(now, 30);
        // Two steps back is rejected at ±1, accepted at ±2.
        String code = TotpGenerator.generate(SECRET, centerCounter - 2, 6, TotpGenerator.Algorithm.SHA1);

        var narrow = TotpVerifier.verify(code, SECRET, now, 30, 1, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(narrow.outcome()).isEqualTo(TotpVerifier.Outcome.WRONG_CODE);

        var wide = TotpVerifier.verify(code, SECRET, now, 30, 2, 6, TotpGenerator.Algorithm.SHA1, null);
        assertThat(wide.outcome()).isEqualTo(TotpVerifier.Outcome.OK_VERIFIED);
    }
}
