package org.alex_melan.obsidianauth.paper;

import org.alex_melan.obsidianauth.core.config.TotpConfig;

/**
 * Shared {@link TotpConfig} factory for paper-plugin tests. Mirrors the {@code config.yml}
 * defaults so test wiring does not have to repeat the fourteen-argument constructor.
 */
public final class TestConfigs {

    private TestConfigs() {
        // static-only
    }

    /** Stock defaults: 6 digits, 30-s step, ±1 window, SHA1, proxy channel disabled. */
    public static TotpConfig totpDefaults() {
        return totp(6, 1, "Minecraft");
    }

    /** Defaults with the three commonly-tuned US3 knobs overridden. */
    public static TotpConfig totp(int digits, int windowSteps, String issuerName) {
        return new TotpConfig(
                digits,
                30,
                windowSteps,
                "SHA1",
                issuerName,
                "{username}",
                5,
                300,
                true,
                "totp.admin.reset",
                "totp.admin.migrate-keys",
                "totp.admin.reload",
                false,
                3000);
    }
}
