package org.alex_melan.obsidianauth.core.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 sign / verify helpers, used by the proxy↔backend channel codec.
 *
 * <p>This is a SYNCHRONOUS primitive — callers MUST wrap invocations on the
 * {@code AsyncExecutor}; never invoke from the main / region thread.
 */
public final class HmacAuthenticator {

    /** Length of an HMAC-SHA256 output. */
    public static final int TAG_LENGTH_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private HmacAuthenticator() {
        // static-only
    }

    public static byte[] sign(byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 sign failed", e);
        }
    }

    /**
     * Constant-time HMAC verification. Uses {@link MessageDigest#isEqual(byte[], byte[])},
     * which is guaranteed not to short-circuit on the first mismatching byte.
     */
    public static boolean verifyConstantTime(byte[] body, byte[] secret, byte[] expectedTag) {
        if (body == null || secret == null || expectedTag == null) {
            return false;
        }
        byte[] actual = sign(body, secret);
        try {
            return MessageDigest.isEqual(actual, expectedTag);
        } finally {
            // Defensive zeroing of the intermediate tag; not strictly necessary because
            // the tag is not a secret, but doesn't hurt.
            java.util.Arrays.fill(actual, (byte) 0);
        }
    }
}
