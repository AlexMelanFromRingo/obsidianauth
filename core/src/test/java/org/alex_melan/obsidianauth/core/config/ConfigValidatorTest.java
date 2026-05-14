package org.alex_melan.obsidianauth.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigValidatorTest {

    private static TotpConfig defaults() {
        return new TotpConfig(
                6,                     // digits
                30,                    // stepSeconds
                1,                     // windowSteps
                "SHA1",                // algorithm
                "ExampleNet",          // issuerName
                "{username}",          // accountLabelTemplate
                5,                     // rateLimitMaxFailures
                300,                   // rateLimitWindowSeconds
                true,                  // kickOnLockout
                "totp.admin.reset",
                "totp.admin.migrate-keys",
                "totp.admin.reload",
                true,                  // proxyChannelEnabled
                3000);                 // proxyChannelTimeoutMs
    }

    @Test
    void defaultsAreAccepted() {
        assertThatNoException().isThrownBy(() -> ConfigValidator.validate(defaults()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 5, 7, 9, 10})
    void rejectsDigitsOutsideAllowedSet(int badDigits) {
        TotpConfig cfg = withDigits(defaults(), badDigits);
        assertThatThrownBy(() -> ConfigValidator.validate(cfg))
                .isInstanceOf(InvalidConfigException.class)
                .satisfies(e -> assertThat(((InvalidConfigException) e).fieldPath())
                        .isEqualTo("totp.digits"));
    }

    @Test
    void acceptsEightDigits() {
        assertThatNoException().isThrownBy(() -> ConfigValidator.validate(withDigits(defaults(), 8)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4, 100})
    void rejectsWindowStepsOutsideRange(int bad) {
        assertThatThrownBy(() -> ConfigValidator.validate(withWindow(defaults(), bad)))
                .isInstanceOf(InvalidConfigException.class)
                .satisfies(e -> assertThat(((InvalidConfigException) e).fieldPath())
                        .isEqualTo("totp.window_steps"));
    }

    @Test
    void rejectsIssuerNameWithColon() {
        TotpConfig cfg = withIssuer(defaults(), "Bad:Name");
        assertThatThrownBy(() -> ConfigValidator.validate(cfg))
                .isInstanceOf(InvalidConfigException.class)
                .satisfies(e -> assertThat(((InvalidConfigException) e).fieldPath())
                        .isEqualTo("issuer.name"));
    }

    @Test
    void rejectsIssuerNameTooLong() {
        TotpConfig cfg = withIssuer(defaults(), "a".repeat(33));
        assertThatThrownBy(() -> ConfigValidator.validate(cfg))
                .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void rejectsZeroRateLimitFailures() {
        TotpConfig cfg = withRateLimit(defaults(), 0, 300);
        assertThatThrownBy(() -> ConfigValidator.validate(cfg))
                .isInstanceOf(InvalidConfigException.class)
                .satisfies(e -> assertThat(((InvalidConfigException) e).fieldPath())
                        .isEqualTo("rate_limit.max_failures_per_window"));
    }

    @Test
    void rejectsUnsupportedAlgorithm() {
        TotpConfig cfg = withAlgorithm(defaults(), "MD5");
        assertThatThrownBy(() -> ConfigValidator.validate(cfg))
                .isInstanceOf(InvalidConfigException.class)
                .satisfies(e -> assertThat(((InvalidConfigException) e).fieldPath())
                        .isEqualTo("totp.algorithm"));
    }

    @Test
    void rejectsPlaintextPassword() {
        assertThatThrownBy(() ->
                ConfigValidator.validatePasswordSource("storage.mysql.password_source", "hunter2"))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("FORBIDDEN");
    }

    @Test
    void acceptsEnvPasswordSource() {
        assertThatNoException().isThrownBy(() ->
                ConfigValidator.validatePasswordSource("storage.mysql.password_source",
                        "env:OBSIDIANAUTH_DB_PASSWORD"));
    }

    @Test
    void acceptsFilePasswordSource() {
        assertThatNoException().isThrownBy(() ->
                ConfigValidator.validatePasswordSource("storage.mysql.password_source",
                        "file:/etc/obsidianauth/db.pw"));
    }

    private static TotpConfig withDigits(TotpConfig c, int d) {
        return new TotpConfig(d, c.stepSeconds(), c.windowSteps(), c.algorithm(), c.issuerName(),
                c.accountLabelTemplate(), c.rateLimitMaxFailures(), c.rateLimitWindowSeconds(),
                c.kickOnLockout(), c.resetPermissionNode(), c.migrateKeysPermissionNode(),
                c.reloadPermissionNode(), c.proxyChannelEnabled(), c.proxyChannelTimeoutMs());
    }

    private static TotpConfig withWindow(TotpConfig c, int w) {
        return new TotpConfig(c.digits(), c.stepSeconds(), w, c.algorithm(), c.issuerName(),
                c.accountLabelTemplate(), c.rateLimitMaxFailures(), c.rateLimitWindowSeconds(),
                c.kickOnLockout(), c.resetPermissionNode(), c.migrateKeysPermissionNode(),
                c.reloadPermissionNode(), c.proxyChannelEnabled(), c.proxyChannelTimeoutMs());
    }

    private static TotpConfig withIssuer(TotpConfig c, String i) {
        return new TotpConfig(c.digits(), c.stepSeconds(), c.windowSteps(), c.algorithm(), i,
                c.accountLabelTemplate(), c.rateLimitMaxFailures(), c.rateLimitWindowSeconds(),
                c.kickOnLockout(), c.resetPermissionNode(), c.migrateKeysPermissionNode(),
                c.reloadPermissionNode(), c.proxyChannelEnabled(), c.proxyChannelTimeoutMs());
    }

    private static TotpConfig withRateLimit(TotpConfig c, int maxFail, int windowSec) {
        return new TotpConfig(c.digits(), c.stepSeconds(), c.windowSteps(), c.algorithm(),
                c.issuerName(), c.accountLabelTemplate(), maxFail, windowSec,
                c.kickOnLockout(), c.resetPermissionNode(), c.migrateKeysPermissionNode(),
                c.reloadPermissionNode(), c.proxyChannelEnabled(), c.proxyChannelTimeoutMs());
    }

    private static TotpConfig withAlgorithm(TotpConfig c, String algo) {
        return new TotpConfig(c.digits(), c.stepSeconds(), c.windowSteps(), algo,
                c.issuerName(), c.accountLabelTemplate(), c.rateLimitMaxFailures(),
                c.rateLimitWindowSeconds(), c.kickOnLockout(), c.resetPermissionNode(),
                c.migrateKeysPermissionNode(), c.reloadPermissionNode(),
                c.proxyChannelEnabled(), c.proxyChannelTimeoutMs());
    }
}
