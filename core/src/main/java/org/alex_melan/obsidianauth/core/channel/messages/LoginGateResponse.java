package org.alex_melan.obsidianauth.core.channel.messages;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.alex_melan.obsidianauth.core.channel.AuthState;

/**
 * Paper → Velocity response carrying the player's current auth state and an opaque
 * 16-byte session token. Velocity treats the token as opaque; Paper rotates it per
 * response so Velocity can detect stale caches.
 *
 * <p>Wire body layout: 1 byte auth_state || 16 bytes session token = 17 bytes total.
 */
public record LoginGateResponse(AuthState state, byte[] opaqueSessionToken) {

    public static final int WIRE_LENGTH = 1 + 16;

    public LoginGateResponse {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(opaqueSessionToken, "opaqueSessionToken");
        if (opaqueSessionToken.length != 16) {
            throw new IllegalArgumentException(
                    "opaqueSessionToken MUST be 16 bytes, got " + opaqueSessionToken.length);
        }
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(WIRE_LENGTH);
        buf.put(state.wireValue());
        buf.put(opaqueSessionToken);
        return buf.array();
    }

    public static LoginGateResponse decode(byte[] body) {
        if (body.length != WIRE_LENGTH) {
            throw new IllegalArgumentException(
                    "LoginGateResponse body must be " + WIRE_LENGTH + " bytes, got " + body.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(body);
        AuthState state = AuthState.fromWire(buf.get());
        byte[] token = new byte[16];
        buf.get(token);
        return new LoginGateResponse(state, token);
    }
}
