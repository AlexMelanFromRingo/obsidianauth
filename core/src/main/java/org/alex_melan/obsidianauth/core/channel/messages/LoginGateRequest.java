package org.alex_melan.obsidianauth.core.channel.messages;

/**
 * Velocity → Paper request: "what is this player's current auth state?"
 *
 * <p>The wire frame carries the player UUID in its header; this body is empty.
 */
public record LoginGateRequest() {

    public static final byte[] EMPTY_BODY = new byte[0];
}
