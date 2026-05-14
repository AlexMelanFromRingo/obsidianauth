package org.alex_melan.obsidianauth.core.config;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a {@link TotpConfig} against the rules in {@code contracts/config-schema.md}.
 *
 * <p>Per FR-025, validation failures MUST cause plugin enable to abort — there is no
 * "fall back to defaults" path. Each rule throws {@link InvalidConfigException} with the
 * specific failing field, so server logs identify exactly which config line to fix.
 */
public final class ConfigValidator {

    private static final Set<Integer> ALLOWED_DIGITS = Set.of(6, 8);
    private static final Set<Integer> ALLOWED_STEP_SECONDS = Set.of(30);
    private static final int MIN_WINDOW_STEPS = 0;
    private static final int MAX_WINDOW_STEPS = 3;
    private static final Set<String> ALLOWED_ALGORITHMS = Set.of("SHA1", "SHA256");
    private static final Pattern ISSUER_NAME = Pattern.compile("^[A-Za-z0-9 _-]{1,32}$");
    private static final Pattern PERMISSION_NODE = Pattern.compile("^[a-z][a-z0-9_.-]*$");
    private static final int MIN_RATE_LIMIT_FAILURES = 1;
    private static final int MAX_RATE_LIMIT_FAILURES = 100;
    private static final int MIN_RATE_LIMIT_WINDOW = 30;
    private static final int MAX_RATE_LIMIT_WINDOW = 3600;
    private static final int MIN_CHANNEL_TIMEOUT_MS = 100;
    private static final int MAX_CHANNEL_TIMEOUT_MS = 30_000;

    private ConfigValidator() {
        // static-only
    }

    /**
     * Validates the supplied configuration. Returns the same instance on success; throws
     * {@link InvalidConfigException} on any rule violation.
     */
    public static TotpConfig validate(TotpConfig cfg) {
        if (!ALLOWED_DIGITS.contains(cfg.digits())) {
            throw new InvalidConfigException("totp.digits",
                    "must be 6 or 8, got " + cfg.digits());
        }
        if (!ALLOWED_STEP_SECONDS.contains(cfg.stepSeconds())) {
            throw new InvalidConfigException("totp.step_seconds",
                    "must be 30 (locked by RFC 6238), got " + cfg.stepSeconds());
        }
        if (cfg.windowSteps() < MIN_WINDOW_STEPS || cfg.windowSteps() > MAX_WINDOW_STEPS) {
            throw new InvalidConfigException("totp.window_steps",
                    "must be in [" + MIN_WINDOW_STEPS + ", " + MAX_WINDOW_STEPS
                            + "], got " + cfg.windowSteps());
        }
        if (!ALLOWED_ALGORITHMS.contains(cfg.algorithm())) {
            throw new InvalidConfigException("totp.algorithm",
                    "must be SHA1 or SHA256, got " + cfg.algorithm());
        }
        if (!ISSUER_NAME.matcher(cfg.issuerName()).matches()) {
            throw new InvalidConfigException("issuer.name",
                    "must match " + ISSUER_NAME.pattern());
        }
        if (cfg.accountLabelTemplate().isBlank()) {
            throw new InvalidConfigException("issuer.account_label",
                    "must be non-empty");
        }
        if (cfg.rateLimitMaxFailures() < MIN_RATE_LIMIT_FAILURES
                || cfg.rateLimitMaxFailures() > MAX_RATE_LIMIT_FAILURES) {
            throw new InvalidConfigException("rate_limit.max_failures_per_window",
                    "must be in [" + MIN_RATE_LIMIT_FAILURES + ", " + MAX_RATE_LIMIT_FAILURES + "]");
        }
        if (cfg.rateLimitWindowSeconds() < MIN_RATE_LIMIT_WINDOW
                || cfg.rateLimitWindowSeconds() > MAX_RATE_LIMIT_WINDOW) {
            throw new InvalidConfigException("rate_limit.window_seconds",
                    "must be in [" + MIN_RATE_LIMIT_WINDOW + ", " + MAX_RATE_LIMIT_WINDOW + "]");
        }
        if (!PERMISSION_NODE.matcher(cfg.resetPermissionNode()).matches()) {
            throw new InvalidConfigException("permissions.reset_node",
                    "must match " + PERMISSION_NODE.pattern());
        }
        if (!PERMISSION_NODE.matcher(cfg.migrateKeysPermissionNode()).matches()) {
            throw new InvalidConfigException("permissions.migrate_keys_node",
                    "must match " + PERMISSION_NODE.pattern());
        }
        if (!PERMISSION_NODE.matcher(cfg.reloadPermissionNode()).matches()) {
            throw new InvalidConfigException("permissions.reload_node",
                    "must match " + PERMISSION_NODE.pattern());
        }
        if (cfg.proxyChannelEnabled()
                && (cfg.proxyChannelTimeoutMs() < MIN_CHANNEL_TIMEOUT_MS
                        || cfg.proxyChannelTimeoutMs() > MAX_CHANNEL_TIMEOUT_MS)) {
            throw new InvalidConfigException("proxy_channel.response_timeout_ms",
                    "must be in [" + MIN_CHANNEL_TIMEOUT_MS + ", " + MAX_CHANNEL_TIMEOUT_MS + "]");
        }
        return cfg;
    }

    /**
     * Validates a {@code password_source} reference. Plaintext passwords are FORBIDDEN
     * (Constitution Security §"Secrets in source"); the only allowed forms are
     * {@code "env:<NAME>"} and {@code "file:<path>"}.
     */
    public static void validatePasswordSource(String fieldPath, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException(fieldPath, "must be non-empty");
        }
        if (!value.startsWith("env:") && !value.startsWith("file:")) {
            throw new InvalidConfigException(fieldPath,
                    "must start with 'env:' or 'file:' (plaintext passwords FORBIDDEN)");
        }
    }
}
