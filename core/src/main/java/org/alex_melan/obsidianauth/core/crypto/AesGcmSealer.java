package org.alex_melan.obsidianauth.core.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM-256 authenticated-encryption helper.
 *
 * <p>Per FR-017, AAD bound at seal time is the concatenation:
 *
 * <pre>
 *     playerUuid (16 bytes, big-endian) || keyVersion (4 bytes, big-endian unsigned)
 * </pre>
 *
 * Any decrypt with a mismatched UUID or key-version raises {@link
 * javax.crypto.AEADBadTagException}, which the caller treats as "this ciphertext is not for
 * this player and key version".
 *
 * <p>This class is a SYNCHRONOUS primitive. Callers (the storage layer) MUST wrap each call
 * onto the {@code AsyncExecutor} — main-thread invocation is forbidden by plan.md §
 * Concurrency Model.
 */
public final class AesGcmSealer {

    /** GCM nonce length per NIST SP 800-38D recommendation. */
    public static final int NONCE_LENGTH_BYTES = 12;
    /** GCM authentication tag length. 128 bits = strongest standard option. */
    public static final int AUTH_TAG_LENGTH_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random;

    public AesGcmSealer() {
        this(new SecureRandom());
    }

    /** Visible for testing — never inject a deterministic random in production. */
    AesGcmSealer(SecureRandom random) {
        this.random = random;
    }

    /**
     * Sealed (ciphertext + nonce + tag) representation of an opened plaintext.
     *
     * @param ciphertext payload bytes; in GCM mode, length == plaintext length
     * @param nonce      {@value #NONCE_LENGTH_BYTES}-byte GCM IV
     * @param authTag    {@value #AUTH_TAG_LENGTH_BITS}-bit authentication tag
     */
    public record Sealed(byte[] ciphertext, byte[] nonce, byte[] authTag) {}

    /**
     * Seal {@code plaintext} under {@code key} with the AAD derived from {@code playerUuid}
     * and {@code key.version()}. Caller is responsible for zeroing {@code plaintext} after
     * this returns (the array is not held).
     */
    public Sealed seal(byte[] plaintext, KeyMaterial key, UUID playerUuid) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        byte[] aad = buildAad(playerUuid, key.version());
        byte[] keyBytes = key.keyCopy();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] cipherAndTag = cipher.doFinal(plaintext);
            // GCM produces ciphertext || tag in one buffer; split them.
            int tagBytes = AUTH_TAG_LENGTH_BITS / 8;
            int ctLen = cipherAndTag.length - tagBytes;
            byte[] ciphertext = Arrays.copyOfRange(cipherAndTag, 0, ctLen);
            byte[] tag = Arrays.copyOfRange(cipherAndTag, ctLen, cipherAndTag.length);
            return new Sealed(ciphertext, nonce, tag);
        } catch (GeneralSecurityException e) {
            // AES-GCM never throws under correct sizing on a properly-installed JCA; treat as fatal.
            throw new IllegalStateException("AES-GCM seal failed", e);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(aad, (byte) 0);
        }
    }

    /**
     * Open a sealed ciphertext under {@code key}. The AAD MUST match the values bound at
     * seal time, otherwise this raises {@link javax.crypto.AEADBadTagException} wrapped in
     * {@link AesGcmAuthenticationException}.
     *
     * <p>The returned plaintext is held by the caller; the caller MUST zero-fill it when
     * done. The caller is also responsible for invoking {@code Arrays.fill(plain, (byte) 0)}
     * before letting the reference escape.
     */
    public byte[] open(Sealed sealed, KeyMaterial key, UUID playerUuid)
            throws AesGcmAuthenticationException {
        byte[] aad = buildAad(playerUuid, key.version());
        byte[] keyBytes = key.keyCopy();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, sealed.nonce()));
            cipher.updateAAD(aad);
            // Reassemble ciphertext || tag for Cipher.doFinal.
            byte[] cipherAndTag = new byte[sealed.ciphertext().length + sealed.authTag().length];
            System.arraycopy(sealed.ciphertext(), 0, cipherAndTag, 0, sealed.ciphertext().length);
            System.arraycopy(sealed.authTag(),    0, cipherAndTag, sealed.ciphertext().length,
                    sealed.authTag().length);
            return cipher.doFinal(cipherAndTag);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new AesGcmAuthenticationException(
                    "AES-GCM tag mismatch — wrong key, wrong UUID, wrong key-version, or tampering",
                    e);
        } catch (GeneralSecurityException e) {
            throw new AesGcmAuthenticationException("AES-GCM open failed", e);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(aad, (byte) 0);
        }
    }

    private static byte[] buildAad(UUID playerUuid, int keyVersion) {
        ByteBuffer buf = ByteBuffer.allocate(16 + 4).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(playerUuid.getMostSignificantBits());
        buf.putLong(playerUuid.getLeastSignificantBits());
        buf.putInt(keyVersion);
        return buf.array();
    }
}
