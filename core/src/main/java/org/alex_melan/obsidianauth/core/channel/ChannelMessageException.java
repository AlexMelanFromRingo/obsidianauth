package org.alex_melan.obsidianauth.core.channel;

/**
 * Thrown by {@link ChannelCodec#decodeAsync} when a frame fails any of: magic, version,
 * body-length cap, frame-length check, HMAC verify, timestamp skew, or nonce replay.
 *
 * <p>Receivers MUST silently drop on this exception (per the contract) and audit-log it
 * once per minute as {@code CHANNEL_HMAC_FAIL}.
 */
public final class ChannelMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChannelMessageException(String message) {
        super(message);
    }
}
