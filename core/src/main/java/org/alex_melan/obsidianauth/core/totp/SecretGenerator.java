package org.alex_melan.obsidianauth.core.totp;

import java.security.SecureRandom;

/**
 * Generates fresh TOTP shared secrets.
 *
 * <p>The default 160-bit (20-byte) secret length matches the RFC 6238 reference and is
 * universally compatible with all authenticator apps. Callers MUST zero-fill the returned
 * byte array when they are done with it.
 */
public final class SecretGenerator {

    /** RFC 6238 default secret length: 160 bits = 20 bytes. */
    public static final int DEFAULT_SECRET_LENGTH_BYTES = 20;

    private final SecureRandom random;

    public SecretGenerator() {
        this(new SecureRandom());
    }

    /** Visible for testing — production code uses {@code SecureRandom}. */
    SecretGenerator(SecureRandom random) {
        this.random = random;
    }

    public byte[] generate() {
        return generate(DEFAULT_SECRET_LENGTH_BYTES);
    }

    public byte[] generate(int lengthBytes) {
        if (lengthBytes < 16) {
            throw new IllegalArgumentException("secret MUST be at least 128 bits (16 bytes)");
        }
        byte[] out = new byte[lengthBytes];
        random.nextBytes(out);
        return out;
    }

    /**
     * RFC 4648 §6 base32 encoder. Authenticator apps universally accept base32-encoded
     * secrets; the optional padding-free variant (no trailing {@code =}) is the form most
     * apps display in their manual-entry UI.
     */
    public static String toBase32(byte[] bytes) {
        return toBase32(bytes, false);
    }

    public static String toBase32(byte[] bytes, boolean withPadding) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        StringBuilder sb = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                int idx = (buffer >> bitsLeft) & 0x1f;
                sb.append(alphabet[idx]);
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(alphabet[idx]);
        }
        if (withPadding) {
            int pad = (8 - sb.length() % 8) % 8;
            for (int i = 0; i < pad; i++) sb.append('=');
        }
        return sb.toString();
    }
}
