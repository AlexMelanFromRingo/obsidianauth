package org.alex_melan.obsidianauth.core.totp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpGeneratorTest {

    /** RFC 6238 Appendix B reference secret: ASCII "12345678901234567890" (20 bytes). */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes();

    /**
     * RFC 6238 Appendix B test vectors for HMAC-SHA1 — 8-digit codes.
     */
    @ParameterizedTest
    @CsvSource({
            "         59, 94287082",
            " 1111111109, 07081804",
            " 1111111111, 14050471",
            " 1234567890, 89005924",
            " 2000000000, 69279037"
    })
    void rfc6238_sha1_8digit(long unixSeconds, String expectedCode) {
        long counter = TotpGenerator.counterFor(unixSeconds, 30);
        String code = TotpGenerator.generate(RFC_SECRET, counter, 8, TotpGenerator.Algorithm.SHA1);
        assertThat(code).isEqualTo(expectedCode);
    }

    @ParameterizedTest
    @CsvSource({
            "         59, 46119246",
            " 1111111109, 68084774",
            " 1111111111, 67062674",
            " 1234567890, 91819424",
            " 2000000000, 90698825"
    })
    void rfc6238_sha256_8digit(long unixSeconds, String expectedCode) {
        // RFC 6238 §5.2 uses a 32-byte SHA-256 secret: same ASCII string repeated to length 32.
        byte[] sha256Secret = "12345678901234567890123456789012".getBytes();
        long counter = TotpGenerator.counterFor(unixSeconds, 30);
        String code = TotpGenerator.generate(sha256Secret, counter, 8, TotpGenerator.Algorithm.SHA256);
        assertThat(code).isEqualTo(expectedCode);
    }
}
