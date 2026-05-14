package org.alex_melan.obsidianauth.core.channel;

import java.util.Objects;
import java.util.UUID;

/**
 * Decoded {@code alex_melan:obsidianauth/v1} frame.
 *
 * <p>Constructed by {@link ChannelCodec#decodeAsync} after the receiver-side validations
 * have passed (magic, version, length, HMAC, timestamp skew, nonce replay). Callers operate
 * on {@code type} + {@code playerUuid} + {@code body}; the framing fields are kept here
 * mostly for symmetry with the encoder path.
 *
 * @param type        message-type discriminant
 * @param playerUuid  the player this message refers to
 * @param nonce       8 random bytes used for receiver-side replay protection
 * @param timestampMs sender's millis-since-epoch (already checked against ±30 s skew)
 * @param body        message-type-specific payload (empty for {@link MessageType#GATE_REQUEST})
 */
public record ChannelMessage(
        MessageType type,
        UUID playerUuid,
        byte[] nonce,
        long timestampMs,
        byte[] body) {

    public ChannelMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(body, "body");
        if (nonce.length != 8) {
            throw new IllegalArgumentException("nonce MUST be 8 bytes, got " + nonce.length);
        }
    }
}
