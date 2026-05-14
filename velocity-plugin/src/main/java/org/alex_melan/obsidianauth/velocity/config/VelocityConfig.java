package org.alex_melan.obsidianauth.velocity.config;

import java.util.Objects;

/**
 * Velocity-side configuration. Smaller than the Paper config — Velocity doesn't see the DB,
 * the AES key, or any TOTP parameters; it only needs to know how to talk to Paper.
 *
 * @param proxyChannelEnabled       Whether to register the {@code alex_melan:obsidianauth/v1} channel.
 * @param hmacSecretSource          Resolved source spec ({@code env:NAME} / {@code file:path}).
 * @param responseTimeoutMs         How long to wait for {@code GATE_RESPONSE} from Paper.
 * @param blockChat                 Whether to cancel chat at the proxy when state is non-AUTHED.
 * @param blockCommands             Whether to cancel commands at the proxy when state is non-AUTHED.
 * @param failClosedRouting         Whether {@code ServerPreConnectEvent} is cancelled when no backend
 *                                  with the auth plugin is reachable.
 * @param loggingLevel              SLF4J level: TRACE / DEBUG / INFO / WARN / ERROR.
 */
public record VelocityConfig(
        boolean proxyChannelEnabled,
        String hmacSecretSource,
        int responseTimeoutMs,
        boolean blockChat,
        boolean blockCommands,
        boolean failClosedRouting,
        String loggingLevel) {

    public VelocityConfig {
        Objects.requireNonNull(hmacSecretSource, "hmacSecretSource");
        Objects.requireNonNull(loggingLevel, "loggingLevel");
    }
}
