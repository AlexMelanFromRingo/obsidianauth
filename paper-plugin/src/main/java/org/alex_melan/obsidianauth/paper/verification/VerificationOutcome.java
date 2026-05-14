package org.alex_melan.obsidianauth.paper.verification;

/**
 * Result of a chat-submitted TOTP verification attempt, as surfaced to the Paper-side
 * listener layer. Mirrors {@link org.alex_melan.obsidianauth.core.totp.TotpVerifier.Outcome}
 * plus the operational outcomes the listener needs to act on.
 */
public enum VerificationOutcome {
    /** Code verified; the player's session is now AUTHED. */
    SUCCESS,
    /** Wrong / malformed / replayed code; the attempt was counted by the rate limiter. */
    FAILED,
    /** Rate limit tripped — the player should be kicked. */
    LOCKED_OUT,
    /** No enrollment record found (should not happen in LOCKED_AWAITING_CODE state). */
    NO_ENROLLMENT,
    /** An unexpected internal error occurred; the player stays locked (failure-closed). */
    INTERNAL_ERROR
}
