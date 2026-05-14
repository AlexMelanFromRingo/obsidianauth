package org.alex_melan.obsidianauth.velocity.config;

import com.electronwill.nightconfig.core.file.FileConfig;
import java.nio.file.Path;
import java.util.Set;
import org.alex_melan.obsidianauth.core.config.InvalidConfigException;

/**
 * Loads {@code velocity.toml} and validates it. Refuses to return on any validation failure.
 */
public final class VelocityConfigLoader {

    private static final Set<String> ALLOWED_LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 30_000;

    private VelocityConfigLoader() {
        // static-only
    }

    public static VelocityConfig load(Path tomlPath) {
        try (FileConfig file = FileConfig.builder(tomlPath).preserveInsertionOrder().build()) {
            file.load();
            VelocityConfig cfg = new VelocityConfig(
                    file.getOrElse("proxy_channel.enabled", Boolean.TRUE),
                    file.getOrElse("proxy_channel.hmac_secret_source", "env:OBSIDIANAUTH_CHANNEL_HMAC"),
                    file.<Number>getOrElse("proxy_channel.response_timeout_ms", 3000).intValue(),
                    file.getOrElse("lockdown.block_chat", Boolean.TRUE),
                    file.getOrElse("lockdown.block_commands", Boolean.TRUE),
                    file.getOrElse("lockdown.fail_closed_routing", Boolean.TRUE),
                    file.getOrElse("logging.level", "INFO"));

            // Validate immediately on load — no silent fallback (FR-025).
            if (cfg.proxyChannelEnabled()) {
                if (cfg.responseTimeoutMs() < MIN_TIMEOUT_MS || cfg.responseTimeoutMs() > MAX_TIMEOUT_MS) {
                    throw new InvalidConfigException("proxy_channel.response_timeout_ms",
                            "must be in [" + MIN_TIMEOUT_MS + ", " + MAX_TIMEOUT_MS + "]");
                }
                if (!cfg.hmacSecretSource().startsWith("env:")
                        && !cfg.hmacSecretSource().startsWith("file:")
                        && !cfg.hmacSecretSource().startsWith("kms:")) {
                    throw new InvalidConfigException("proxy_channel.hmac_secret_source",
                            "must start with env:, file:, or kms:");
                }
            }
            if (!ALLOWED_LOG_LEVELS.contains(cfg.loggingLevel())) {
                throw new InvalidConfigException("logging.level",
                        "must be one of " + ALLOWED_LOG_LEVELS);
            }
            return cfg;
        }
    }
}
