package org.alex_melan.obsidianauth.core.channel.messages;

/**
 * Paper → Velocity broadcast: "drop your cached state for this player".
 *
 * <p>Wire body layout: 1 byte reason.
 */
public record AuthStateInvalidate(Reason reason) {

    public static final int WIRE_LENGTH = 1;

    public enum Reason {
        ADMIN_RESET((byte) 0x00),
        LOCKOUT((byte) 0x01),
        LOGOUT((byte) 0x02),
        OTHER((byte) 0xFF);

        private final byte wireValue;

        Reason(byte wireValue) {
            this.wireValue = wireValue;
        }

        public byte wireValue() {
            return wireValue;
        }

        public static Reason fromWire(byte v) {
            for (Reason r : values()) {
                if (r.wireValue == v) return r;
            }
            return OTHER;
        }
    }

    public byte[] encode() {
        return new byte[] { reason.wireValue() };
    }

    public static AuthStateInvalidate decode(byte[] body) {
        if (body.length != WIRE_LENGTH) {
            throw new IllegalArgumentException(
                    "AuthStateInvalidate body must be 1 byte, got " + body.length);
        }
        return new AuthStateInvalidate(Reason.fromWire(body[0]));
    }
}
