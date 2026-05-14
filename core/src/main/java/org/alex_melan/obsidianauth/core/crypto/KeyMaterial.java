package org.alex_melan.obsidianauth.core.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * A 256-bit AES key together with its monotonic version identifier.
 *
 * <p>The {@code key} byte array is mutable so it can be zeroed via {@link #wipe()} when the
 * caller is done with it. Per Constitution Principle IV, the plaintext key MUST be in memory
 * for the shortest possible window.
 */
public final class KeyMaterial {

    /** The expected length of a master key. AES-256 = 32 bytes. */
    public static final int KEY_LENGTH_BYTES = 32;

    private final int version;
    private final byte[] key;

    public KeyMaterial(int version, byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "expected " + KEY_LENGTH_BYTES + "-byte key, got " + key.length);
        }
        if (version <= 0) {
            throw new IllegalArgumentException("key version MUST be positive, got " + version);
        }
        this.version = version;
        this.key = key.clone();
    }

    public int version() {
        return version;
    }

    /** Returns a defensive copy. Caller is responsible for zeroing the copy. */
    public byte[] keyCopy() {
        return key.clone();
    }

    /** Zeroes the held key bytes. After calling, {@link #keyCopy()} returns all zeros. */
    public void wipe() {
        Arrays.fill(key, (byte) 0);
    }
}
