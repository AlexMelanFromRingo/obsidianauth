package org.alex_melan.obsidianauth.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmSealerTest {

    private static final byte[] PLAINTEXT_SECRET = {
            // RFC 6238 standard 20-byte secret
            0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            0x12, 0x34, 0x56, 0x78, (byte) 0x90, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            0x12, 0x34, 0x56, 0x78
    };

    private final AesGcmSealer sealer = new AesGcmSealer();

    private KeyMaterial newKey(int version) {
        byte[] raw = new byte[KeyMaterial.KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(raw);
        return new KeyMaterial(version, raw);
    }

    @Test
    void roundTrip_recoversPlaintextWithCorrectAad() throws Exception {
        UUID uuid = UUID.randomUUID();
        KeyMaterial key = newKey(1);

        AesGcmSealer.Sealed sealed = sealer.seal(PLAINTEXT_SECRET.clone(), key, uuid);
        byte[] recovered = sealer.open(sealed, key, uuid);

        assertThat(recovered).containsExactly(PLAINTEXT_SECRET);

        Arrays.fill(recovered, (byte) 0);
        key.wipe();
    }

    @Test
    void open_rejectsMismatchedUuid_throwsAuthenticationException() {
        KeyMaterial key = newKey(1);
        AesGcmSealer.Sealed sealed = sealer.seal(PLAINTEXT_SECRET.clone(), key, UUID.randomUUID());

        assertThatThrownBy(() -> sealer.open(sealed, key, UUID.randomUUID()))
                .isInstanceOf(AesGcmAuthenticationException.class)
                .hasMessageContaining("tag mismatch");
    }

    @Test
    void open_rejectsMismatchedKeyVersion_throwsAuthenticationException() {
        UUID uuid = UUID.randomUUID();
        byte[] rawKey = new byte[KeyMaterial.KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(rawKey);
        KeyMaterial keyV1 = new KeyMaterial(1, rawKey);
        KeyMaterial keyV2 = new KeyMaterial(2, rawKey);          // same bytes, different version
        AesGcmSealer.Sealed sealed = sealer.seal(PLAINTEXT_SECRET.clone(), keyV1, uuid);

        assertThatThrownBy(() -> sealer.open(sealed, keyV2, uuid))
                .isInstanceOf(AesGcmAuthenticationException.class);
    }

    @Test
    void open_rejectsCiphertextTamper_throwsAuthenticationException() {
        UUID uuid = UUID.randomUUID();
        KeyMaterial key = newKey(1);
        AesGcmSealer.Sealed sealed = sealer.seal(PLAINTEXT_SECRET.clone(), key, uuid);

        byte[] tamperedCt = sealed.ciphertext().clone();
        tamperedCt[0] ^= 0x01;
        AesGcmSealer.Sealed tampered =
                new AesGcmSealer.Sealed(tamperedCt, sealed.nonce(), sealed.authTag());

        assertThatThrownBy(() -> sealer.open(tampered, key, uuid))
                .isInstanceOf(AesGcmAuthenticationException.class);
    }

    @Test
    void open_rejectsAuthTagTamper_throwsAuthenticationException() {
        UUID uuid = UUID.randomUUID();
        KeyMaterial key = newKey(1);
        AesGcmSealer.Sealed sealed = sealer.seal(PLAINTEXT_SECRET.clone(), key, uuid);

        byte[] tamperedTag = sealed.authTag().clone();
        tamperedTag[tamperedTag.length - 1] ^= 0x01;
        AesGcmSealer.Sealed tampered =
                new AesGcmSealer.Sealed(sealed.ciphertext(), sealed.nonce(), tamperedTag);

        assertThatThrownBy(() -> sealer.open(tampered, key, uuid))
                .isInstanceOf(AesGcmAuthenticationException.class);
    }

    @Test
    void seal_producesDistinctCiphertextsAcrossInvocations() {
        UUID uuid = UUID.randomUUID();
        KeyMaterial key = newKey(1);

        AesGcmSealer.Sealed s1 = sealer.seal(PLAINTEXT_SECRET.clone(), key, uuid);
        AesGcmSealer.Sealed s2 = sealer.seal(PLAINTEXT_SECRET.clone(), key, uuid);

        // Nonces MUST be unique per invocation under a fixed key.
        assertThat(s1.nonce()).isNotEqualTo(s2.nonce());
        assertThat(s1.ciphertext()).isNotEqualTo(s2.ciphertext());
    }
}
