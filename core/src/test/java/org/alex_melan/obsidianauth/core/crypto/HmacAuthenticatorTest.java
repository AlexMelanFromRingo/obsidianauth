package org.alex_melan.obsidianauth.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class HmacAuthenticatorTest {

    @Test
    void signVerifyRoundTrip() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        byte[] body = "hello world".getBytes();

        byte[] tag = HmacAuthenticator.sign(body, secret);

        assertThat(tag).hasSize(HmacAuthenticator.TAG_LENGTH_BYTES);
        assertThat(HmacAuthenticator.verifyConstantTime(body, secret, tag)).isTrue();
    }

    @Test
    void verifyRejectsTamperedBody() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        byte[] body = "hello world".getBytes();
        byte[] tag = HmacAuthenticator.sign(body, secret);

        byte[] tamperedBody = body.clone();
        tamperedBody[0] ^= 0x01;

        assertThat(HmacAuthenticator.verifyConstantTime(tamperedBody, secret, tag)).isFalse();
    }

    @Test
    void verifyRejectsTamperedTag() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        byte[] body = "hello world".getBytes();
        byte[] tag = HmacAuthenticator.sign(body, secret);

        byte[] tamperedTag = tag.clone();
        tamperedTag[tamperedTag.length - 1] ^= 0x01;

        assertThat(HmacAuthenticator.verifyConstantTime(body, secret, tamperedTag)).isFalse();
    }

    @Test
    void verifyRejectsWrongSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        byte[] otherSecret = new byte[32];
        new SecureRandom().nextBytes(otherSecret);
        byte[] body = "hello world".getBytes();
        byte[] tag = HmacAuthenticator.sign(body, secret);

        assertThat(HmacAuthenticator.verifyConstantTime(body, otherSecret, tag)).isFalse();
    }

    @Test
    void verifyHandlesNullSafely() {
        byte[] secret = new byte[32];
        byte[] body = new byte[10];
        byte[] tag = new byte[32];
        assertThat(HmacAuthenticator.verifyConstantTime(null, secret, tag)).isFalse();
        assertThat(HmacAuthenticator.verifyConstantTime(body, null, tag)).isFalse();
        assertThat(HmacAuthenticator.verifyConstantTime(body, secret, null)).isFalse();
    }
}
