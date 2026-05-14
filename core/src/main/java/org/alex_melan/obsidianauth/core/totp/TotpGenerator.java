package org.alex_melan.obsidianauth.core.totp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP code generator.
 *
 * <p>SYNCHRONOUS primitive — every {@code Mac.doFinal} call MUST be wrapped on the
 * {@code AsyncExecutor} by service-layer callers. Never invoke from the main thread.
 *
 * <p>Supports HMAC-SHA1 (RFC 6238 default) and HMAC-SHA256.
 */
public final class TotpGenerator {

    public enum Algorithm {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256");

        final String jcaName;
        Algorithm(String jcaName) { this.jcaName = jcaName; }

        public static Algorithm fromConfigName(String name) {
            return switch (name.toUpperCase()) {
                case "SHA1"   -> SHA1;
                case "SHA256" -> SHA256;
                default       -> throw new IllegalArgumentException("Unsupported TOTP algorithm: " + name);
            };
        }
    }

    private TotpGenerator() {
        // static-only
    }

    /**
     * Generate the TOTP code for the given counter value.
     *
     * @param secret    shared secret (raw bytes; caller is responsible for base32 decoding)
     * @param counter   RFC 6238 counter = floor(timeSeconds / stepSeconds)
     * @param digits    number of digits in the output (6 or 8)
     * @param algorithm HMAC algorithm
     * @return zero-padded numeric string of length {@code digits}
     */
    public static String generate(byte[] secret, long counter, int digits, Algorithm algorithm) {
        if (digits != 6 && digits != 8) {
            throw new IllegalArgumentException("digits must be 6 or 8");
        }
        try {
            Mac mac = Mac.getInstance(algorithm.jcaName);
            mac.init(new SecretKeySpec(secret, algorithm.jcaName));

            byte[] counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array();
            byte[] hash = mac.doFinal(counterBytes);

            // RFC 6238 dynamic truncation.
            int offset = hash[hash.length - 1] & 0x0f;
            int binary =
                    ((hash[offset]     & 0x7f) << 24)
                  | ((hash[offset + 1] & 0xff) << 16)
                  | ((hash[offset + 2] & 0xff) <<  8)
                  | ((hash[offset + 3] & 0xff));

            int modulo = (int) Math.pow(10, digits);
            int code = binary % modulo;
            return String.format("%0" + digits + "d", code);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC " + algorithm.jcaName + " unavailable", e);
        }
    }

    /** Compute the RFC 6238 counter for a given Unix-seconds timestamp. */
    public static long counterFor(long unixSeconds, int stepSeconds) {
        if (stepSeconds <= 0) throw new IllegalArgumentException("stepSeconds must be positive");
        return unixSeconds / stepSeconds;
    }
}
