package org.alex_melan.obsidianauth.paper.config;

import org.alex_melan.obsidianauth.core.config.ConfigValidator;
import org.alex_melan.obsidianauth.core.config.InvalidConfigException;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Translates Bukkit's {@code config.yml} into the platform-neutral {@link TotpConfig} and
 * runs {@link ConfigValidator}. Refuses to return a config on any validation failure —
 * callers (the plugin's {@code onEnable}) MUST treat the resulting {@link
 * InvalidConfigException} as fatal (FR-025).
 */
public final class PaperConfigLoader {

    private PaperConfigLoader() {
        // static-only
    }

    public static TotpConfig load(FileConfiguration yaml) {
        // Validate password source eagerly: even when SQLite is selected, MySQL config shape
        // is still validated so misconfigurations don't lurk silently for a future backend swap.
        String passwordSource = yaml.getString("storage.mysql.password_source", "");
        if (!passwordSource.isEmpty()) {
            ConfigValidator.validatePasswordSource("storage.mysql.password_source", passwordSource);
        }

        TotpConfig cfg = new TotpConfig(
                yaml.getInt("totp.digits", 6),
                yaml.getInt("totp.step_seconds", 30),
                yaml.getInt("totp.window_steps", 1),
                yaml.getString("totp.algorithm", "SHA1"),
                yaml.getString("issuer.name", "Minecraft"),
                yaml.getString("issuer.account_label", "{username}"),
                yaml.getInt("rate_limit.max_failures_per_window", 5),
                yaml.getInt("rate_limit.window_seconds", 300),
                yaml.getBoolean("rate_limit.kick_on_lockout", true),
                yaml.getString("permissions.reset_node", "totp.admin.reset"),
                yaml.getString("permissions.migrate_keys_node", "totp.admin.migrate-keys"),
                yaml.getString("permissions.reload_node", "totp.admin.reload"),
                yaml.getBoolean("proxy_channel.enabled", true),
                yaml.getInt("proxy_channel.response_timeout_ms", 3000));
        return ConfigValidator.validate(cfg);
    }
}
