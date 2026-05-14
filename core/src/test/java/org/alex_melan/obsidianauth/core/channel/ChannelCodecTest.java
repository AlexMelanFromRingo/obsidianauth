package org.alex_melan.obsidianauth.core.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.alex_melan.obsidianauth.core.channel.messages.LoginGateResponse;
import org.junit.jupiter.api.Test;

class ChannelCodecTest {

    private static final byte[] SECRET = new byte[32];
    static {
        new SecureRandom().nextBytes(SECRET);
    }

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final ChannelCodec codec = new ChannelCodec(
            new ImmediateAsyncExecutor(), new SecureRandom(), now::get);

    private ChannelMessage gateRequest() {
        return new ChannelMessage(MessageType.GATE_REQUEST,
                UUID.randomUUID(), codec.freshNonce(), codec.now(), new byte[0]);
    }

    @Test
    void roundTripGateRequest() {
        ChannelMessage msg = gateRequest();
        byte[] frame = codec.encodeSync(msg, SECRET);
        ChannelMessage decoded = codec.decodeSync(frame, SECRET);

        assertThat(decoded.type()).isEqualTo(MessageType.GATE_REQUEST);
        assertThat(decoded.playerUuid()).isEqualTo(msg.playerUuid());
        assertThat(decoded.body()).isEmpty();
    }

    @Test
    void roundTripGateResponseBody() {
        byte[] token = new byte[16];
        new SecureRandom().nextBytes(token);
        byte[] body = new LoginGateResponse(AuthState.AUTHED, token).encode();
        ChannelMessage msg = new ChannelMessage(MessageType.GATE_RESPONSE,
                UUID.randomUUID(), codec.freshNonce(), codec.now(), body);
        byte[] frame = codec.encodeSync(msg, SECRET);
        ChannelMessage decoded = codec.decodeSync(frame, SECRET);
        LoginGateResponse decodedBody = LoginGateResponse.decode(decoded.body());

        assertThat(decodedBody.state()).isEqualTo(AuthState.AUTHED);
        assertThat(decodedBody.opaqueSessionToken()).containsExactly(token);
    }

    @Test
    void rejectsBadMagic() {
        byte[] frame = codec.encodeSync(gateRequest(), SECRET);
        frame[0] ^= 1;
        assertThatThrownBy(() -> codec.decodeSync(frame, SECRET))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void rejectsBadVersion() {
        byte[] frame = codec.encodeSync(gateRequest(), SECRET);
        frame[4] = 0x42;
        assertThatThrownBy(() -> codec.decodeSync(frame, SECRET))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsHmacTamper() {
        byte[] frame = codec.encodeSync(gateRequest(), SECRET);
        frame[frame.length - 1] ^= 1;
        assertThatThrownBy(() -> codec.decodeSync(frame, SECRET))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("HMAC");
    }

    @Test
    void rejectsBodyTamper() {
        byte[] token = new byte[16];
        new SecureRandom().nextBytes(token);
        byte[] body = new LoginGateResponse(AuthState.AUTHED, token).encode();
        ChannelMessage msg = new ChannelMessage(MessageType.GATE_RESPONSE,
                UUID.randomUUID(), codec.freshNonce(), codec.now(), body);
        byte[] frame = codec.encodeSync(msg, SECRET);
        // Flip a body byte (somewhere after the header).
        frame[ChannelCodec.HEADER_LENGTH] ^= 1;
        assertThatThrownBy(() -> codec.decodeSync(frame, SECRET))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("HMAC");
    }

    @Test
    void rejectsExpiredTimestamp() {
        byte[] frame = codec.encodeSync(gateRequest(), SECRET);
        // Move the clock far forward.
        now.addAndGet(60_000);
        assertThatThrownBy(() -> codec.decodeSync(frame, SECRET))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void rejectsReplayedNonce() {
        ChannelMessage msg = gateRequest();
        byte[] frame = codec.encodeSync(msg, SECRET);
        codec.decodeSync(frame, SECRET);
        assertThatThrownBy(() -> codec.decodeSync(frame, SECRET))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("replay");
    }

    @Test
    void rejectsWrongSecret() {
        byte[] frame = codec.encodeSync(gateRequest(), SECRET);
        byte[] otherSecret = new byte[32];
        new SecureRandom().nextBytes(otherSecret);
        assertThatThrownBy(() -> codec.decodeSync(frame, otherSecret))
                .isInstanceOf(ChannelMessageException.class)
                .hasMessageContaining("HMAC");
    }
}
