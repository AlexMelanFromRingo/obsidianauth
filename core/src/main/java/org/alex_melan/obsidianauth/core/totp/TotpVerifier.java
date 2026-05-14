package org.alex_melan.obsidianauth.core.totp;

import java.util.Objects;

/**
 * RFC 6238 TOTP verifier with ±N step window and replay protection.
 *
 * <p>SYNCHRONOUS primitive — callers wrap on the {@code AsyncExecutor}.
 */
public final class TotpVerifier {

    /**
     * Outcome of a single verification attempt.
     */
    public enum Outcome {
        /** Code matched a step within the window and that step had not yet been consumed. */
        OK_VERIFIED,
        /** Code didn't match any step in the window. */
        WRONG_CODE,
        /** Code matched a step, but that step was already consumed for this player. */
        REPLAYED,
        /** Submitted code's length didn't match the configured digit count. */
        MALFORMED
    }

    /** Resulting state to feed back into {@code EnrollmentDao.recordVerification}. */
    public record VerificationResult(Outcome outcome, long matchedCounter) {}

    private TotpVerifier() {
        // static-only
    }

    /**
     * Verify a submitted code against the stored secret.
     *
     * @param submittedCode       digits the player typed in chat
     * @param secret              raw secret bytes
     * @param nowSeconds          current Unix time in seconds
     * @param stepSeconds         RFC 6238 step (30)
     * @param windowSteps         ±N step tolerance
     * @param digits              expected digit count
     * @param algorithm           HMAC algorithm
     * @param lastConsumedCounter the most-recent counter this player has already consumed
     *                            ({@code null} if never verified)
     * @return outcome plus the matching counter (only meaningful for {@code OK_VERIFIED})
     */
    public static VerificationResult verify(
            String submittedCode,
            byte[] secret,
            long nowSeconds,
            int stepSeconds,
            int windowSteps,
            int digits,
            TotpGenerator.Algorithm algorithm,
            Long lastConsumedCounter) {

        Objects.requireNonNull(submittedCode, "submittedCode");
        if (submittedCode.length() != digits || !submittedCode.chars().allMatch(Character::isDigit)) {
            return new VerificationResult(Outcome.MALFORMED, 0L);
        }
        long centerCounter = TotpGenerator.counterFor(nowSeconds, stepSeconds);
        for (int offset = -windowSteps; offset <= windowSteps; offset++) {
            long c = centerCounter + offset;
            if (c < 0) continue;
            String expected = TotpGenerator.generate(secret, c, digits, algorithm);
            if (constantTimeEquals(expected, submittedCode)) {
                if (lastConsumedCounter != null && c <= lastConsumedCounter) {
                    return new VerificationResult(Outcome.REPLAYED, c);
                }
                return new VerificationResult(Outcome.OK_VERIFIED, c);
            }
        }
        return new VerificationResult(Outcome.WRONG_CODE, 0L);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
