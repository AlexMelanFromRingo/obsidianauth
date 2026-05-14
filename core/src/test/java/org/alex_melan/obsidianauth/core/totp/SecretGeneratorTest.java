package org.alex_melan.obsidianauth.core.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretGeneratorTest {

    @Test
    void generate_defaultLengthIs160Bits() {
        SecretGenerator gen = new SecretGenerator();
        byte[] secret = gen.generate();
        assertThat(secret).hasSize(20);
    }

    @Test
    void generate_rejectsTooShortLength() {
        SecretGenerator gen = new SecretGenerator();
        assertThatThrownBy(() -> gen.generate(15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generate_producesDistinctSecrets() {
        SecretGenerator gen = new SecretGenerator();
        byte[] s1 = gen.generate();
        byte[] s2 = gen.generate();
        assertThat(s1).isNotEqualTo(s2);
    }

    @Test
    void toBase32_roundTripsKnownVector() {
        // RFC 4648 §10 test vector: "foobar" → "MZXW6YTBOI" (unpadded)
        byte[] input = "foobar".getBytes();
        assertThat(SecretGenerator.toBase32(input)).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void toBase32_addsPaddingWhenRequested() {
        byte[] input = "foob".getBytes();
        // "foob" → 4 bytes → 32 bits → 7 base32 chars, 1 padding char to reach 8.
        assertThat(SecretGenerator.toBase32(input, true)).endsWith("=");
        assertThat(SecretGenerator.toBase32(input, false)).doesNotContain("=");
    }
}
