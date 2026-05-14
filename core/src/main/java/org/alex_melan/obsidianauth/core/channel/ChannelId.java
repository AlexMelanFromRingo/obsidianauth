package org.alex_melan.obsidianauth.core.channel;

/**
 * Canonical plugin-message channel identifier shared by Paper and Velocity.
 *
 * <p>Namespaced under the project's canonical {@code alex_melan} prefix per Constitution
 * Principle V. The {@code /v1} suffix reserves room for forward-incompatible protocol
 * changes — any breaking change to the wire format ships under a new channel ID
 * ({@code /v2}) and the codec is taught to refuse unknown major versions.
 */
public final class ChannelId {

    public static final String ID = "alex_melan:obsidianauth/v1";

    private ChannelId() {
        // constant-only
    }
}
