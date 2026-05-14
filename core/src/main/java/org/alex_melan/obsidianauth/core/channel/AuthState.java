package org.alex_melan.obsidianauth.core.channel;

/**
 * Auth state mirror carried in {@link MessageType#GATE_RESPONSE} bodies, and held
 * in-memory on the Velocity side as the {@code lastKnownState} of a player.
 *
 * <p>{@link #UNKNOWN} is a Velocity-internal default for "we haven't asked Paper yet" or
 * "Paper hasn't answered within the configured timeout". It NEVER appears on the wire.
 */
public enum AuthState {
    AUTHED((byte) 0x00),
    PENDING((byte) 0x01),
    LOCKED_OUT((byte) 0x02),
    /** In-memory only; never serialised. */
    UNKNOWN((byte) -1);

    private final byte wireValue;

    AuthState(byte wireValue) {
        this.wireValue = wireValue;
    }

    public byte wireValue() {
        return wireValue;
    }

    public static AuthState fromWire(byte value) {
        for (AuthState s : values()) {
            if (s.wireValue == value && s != UNKNOWN) return s;
        }
        throw new IllegalArgumentException("Unknown AuthState wire value: 0x" + Integer.toHexString(value & 0xff));
    }
}
