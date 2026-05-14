package org.alex_melan.obsidianauth.core.channel;

/**
 * Wire-level message type discriminant.
 *
 * <p>See {@code contracts/plugin-message-channel.md} §"Message types" for the body payload
 * shape associated with each variant.
 */
public enum MessageType {
    /** Velocity → Paper: "is this player currently authenticated?" Body empty. */
    GATE_REQUEST((byte) 0x01),
    /** Paper → Velocity: auth state + opaque session token. Body = 17 bytes. */
    GATE_RESPONSE((byte) 0x02),
    /** Paper → Velocity: drop your cached state for this player. Body = 1 byte. */
    INVALIDATE((byte) 0x03);

    private final byte wireValue;

    MessageType(byte wireValue) {
        this.wireValue = wireValue;
    }

    public byte wireValue() {
        return wireValue;
    }

    public static MessageType fromWire(byte value) {
        for (MessageType t : values()) {
            if (t.wireValue == value) return t;
        }
        throw new IllegalArgumentException("Unknown MessageType wire value: 0x" + Integer.toHexString(value & 0xff));
    }
}
