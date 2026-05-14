package org.alex_melan.obsidianauth.core.config;

import java.util.Objects;

/**
 * Immutable runtime configuration of the ObsidianAuth plugin.
 *
 * <p>Constructed by platform-specific loaders ({@code PaperConfigLoader}, {@code
 * VelocityConfigLoader}), validated by {@link ConfigValidator}. Every value here is either
 * directly user-tunable (digits, window steps, issuer name) or derived from a user-tunable
 * configuration field at load time (e.g., the resolved permission node strings).
 *
 * @param digits                   TOTP code length. Allowed: {@code 6} or {@code 8}.
 * @param stepSeconds              RFC 6238 time step. Locked to {@code 30}.
 * @param windowSteps              Verification tolerance in steps either side of {@code now}.
 *                                 Allowed range: {@code [0, 3]}.
 * @param algorithm                HMAC algorithm. One of {@code SHA1}, {@code SHA256}.
 * @param issuerName               TOTP "issuer" label rendered in the authenticator app.
 *                                 Validation regex: {@code ^[A-Za-z0-9 _-]{1,32}$}.
 * @param accountLabelTemplate     Template for the account portion of the TOTP URI; may
 *                                 contain the literal {@code "{username}"} placeholder.
 * @param rateLimitMaxFailures     Failed verifications per window before lockout.
 * @param rateLimitWindowSeconds   Sliding-window size for rate-limiting.
 * @param kickOnLockout            Whether to kick the player when the lockout threshold is hit.
 * @param resetPermissionNode      Bukkit permission node for {@code /2fa-admin reset}.
 * @param migrateKeysPermissionNode Bukkit permission node for {@code /2fa-admin migrate-keys}.
 * @param reloadPermissionNode     Bukkit permission node for {@code /2fa-admin reload}.
 * @param proxyChannelEnabled      Whether the Paper module participates in the proxy channel.
 * @param proxyChannelTimeoutMs    Timeout for awaiting a {@code GATE_RESPONSE} from Paper.
 */
public record TotpConfig(
        int digits,
        int stepSeconds,
        int windowSteps,
        String algorithm,
        String issuerName,
        String accountLabelTemplate,
        int rateLimitMaxFailures,
        int rateLimitWindowSeconds,
        boolean kickOnLockout,
        String resetPermissionNode,
        String migrateKeysPermissionNode,
        String reloadPermissionNode,
        boolean proxyChannelEnabled,
        int proxyChannelTimeoutMs) {

    public TotpConfig {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(issuerName, "issuerName");
        Objects.requireNonNull(accountLabelTemplate, "accountLabelTemplate");
        Objects.requireNonNull(resetPermissionNode, "resetPermissionNode");
        Objects.requireNonNull(migrateKeysPermissionNode, "migrateKeysPermissionNode");
        Objects.requireNonNull(reloadPermissionNode, "reloadPermissionNode");
    }
}
