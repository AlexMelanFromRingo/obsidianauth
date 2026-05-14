package org.alex_melan.obsidianauth.core.channel;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.crypto.HmacAuthenticator;

/**
 * Encode / decode {@code alex_melan:obsidianauth/v1} frames per the wire format in
 * {@code contracts/plugin-message-channel.md}.
 *
 * <p>Header layout (all multi-byte fields big-endian):
 *
 * <pre>
 *  0   magic           4 bytes  ASCII "TOTP"
 *  4   version         1 byte   0x01
 *  5   message_type    1 byte   0x01 / 0x02 / 0x03
 *  6   player_uuid    16 bytes
 * 22   nonce           8 bytes
 * 30   timestamp_ms    8 bytes  int64
 * 38   body_length     4 bytes  uint32 (0..4096)
 * 42   body            L bytes
 * 42+L hmac           32 bytes  HMAC-SHA256 over bytes[0..(42+L-1)]
 * </pre>
 *
 * <p>HMAC verification, like every other crypto operation, runs on the
 * {@link AsyncExecutor}.
 */
public final class ChannelCodec {

    public static final byte[] MAGIC = {'T', 'O', 'T', 'P'};
    public static final byte VERSION = 0x01;
    public static final int HEADER_LENGTH = 42;
    public static final int MAX_BODY_LENGTH = 4096;
    /** Maximum acceptable skew between sender and receiver clocks. */
    public static final long MAX_TIMESTAMP_SKEW_MS = 30_000L;
    /** Nonce-replay window — frames older than this fall off the LRU. */
    public static final long NONCE_REPLAY_WINDOW_MS = 60_000L;
    /** LRU cap on the per-UUID nonce table per direction. */
    public static final int NONCE_LRU_MAX_ENTRIES = 4096;

    private final AsyncExecutor async;
    private final SecureRandom random;
    private final java.util.function.LongSupplier clock;
    private final NonceLru seenNonces;

    public ChannelCodec(AsyncExecutor async) {
        this(async, new SecureRandom(), System::currentTimeMillis);
    }

    /** Visible for testing. */
    ChannelCodec(AsyncExecutor async, SecureRandom random, java.util.function.LongSupplier clock) {
        this.async = async;
        this.random = random;
        this.clock = clock;
        this.seenNonces = new NonceLru(NONCE_LRU_MAX_ENTRIES);
    }

    public CompletableFuture<byte[]> encodeAsync(ChannelMessage msg, byte[] hmacSecret) {
        return async.submit(() -> encodeSync(msg, hmacSecret));
    }

    public CompletableFuture<ChannelMessage> decodeAsync(byte[] frame, byte[] hmacSecret) {
        return async.submit(() -> decodeSync(frame, hmacSecret));
    }

    /** Visible for testing. */
    byte[] encodeSync(ChannelMessage msg, byte[] hmacSecret) {
        if (msg.body().length > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "body length " + msg.body().length + " exceeds cap " + MAX_BODY_LENGTH);
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LENGTH + msg.body().length + HmacAuthenticator.TAG_LENGTH_BYTES);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.put(msg.type().wireValue());
        buf.putLong(msg.playerUuid().getMostSignificantBits());
        buf.putLong(msg.playerUuid().getLeastSignificantBits());
        buf.put(msg.nonce());
        buf.putLong(msg.timestampMs());
        buf.putInt(msg.body().length);
        buf.put(msg.body());
        // HMAC over everything written so far.
        byte[] bodyForSign = Arrays.copyOf(buf.array(), HEADER_LENGTH + msg.body().length);
        byte[] tag = HmacAuthenticator.sign(bodyForSign, hmacSecret);
        buf.put(tag);
        return buf.array();
    }

    /** Visible for testing. */
    ChannelMessage decodeSync(byte[] frame, byte[] hmacSecret) {
        if (frame == null || frame.length < HEADER_LENGTH + HmacAuthenticator.TAG_LENGTH_BYTES) {
            throw new ChannelMessageException("frame too short");
        }
        if (frame[0] != MAGIC[0] || frame[1] != MAGIC[1] || frame[2] != MAGIC[2] || frame[3] != MAGIC[3]) {
            throw new ChannelMessageException("bad magic");
        }
        if (frame[4] != VERSION) {
            throw new ChannelMessageException("unsupported version 0x" + Integer.toHexString(frame[4] & 0xff));
        }
        ByteBuffer buf = ByteBuffer.wrap(frame);
        buf.position(38);
        int bodyLen = buf.getInt();
        if (bodyLen < 0 || bodyLen > MAX_BODY_LENGTH) {
            throw new ChannelMessageException("body length out of range: " + bodyLen);
        }
        int expectedFrameLen = HEADER_LENGTH + bodyLen + HmacAuthenticator.TAG_LENGTH_BYTES;
        if (frame.length != expectedFrameLen) {
            throw new ChannelMessageException(
                    "frame length " + frame.length + " != header+body+tag " + expectedFrameLen);
        }
        // HMAC verify on bytes[0..(42+bodyLen-1)].
        byte[] body = Arrays.copyOf(frame, HEADER_LENGTH + bodyLen);
        byte[] tag = Arrays.copyOfRange(frame, HEADER_LENGTH + bodyLen, frame.length);
        if (!HmacAuthenticator.verifyConstantTime(body, hmacSecret, tag)) {
            throw new ChannelMessageException("HMAC verification failed");
        }
        // Skew check on timestamp.
        ByteBuffer view = ByteBuffer.wrap(frame);
        MessageType type;
        try {
            type = MessageType.fromWire(view.get(5));
        } catch (IllegalArgumentException e) {
            throw new ChannelMessageException(e.getMessage());
        }
        long msb = view.getLong(6);
        long lsb = view.getLong(14);
        UUID uuid = new UUID(msb, lsb);
        byte[] nonce = new byte[8];
        System.arraycopy(frame, 22, nonce, 0, 8);
        long timestampMs = view.getLong(30);
        long now = clock.getAsLong();
        if (Math.abs(now - timestampMs) > MAX_TIMESTAMP_SKEW_MS) {
            throw new ChannelMessageException("timestamp skew exceeds " + MAX_TIMESTAMP_SKEW_MS + " ms");
        }
        // Replay check.
        synchronized (seenNonces) {
            NonceKey key = new NonceKey(uuid, ByteBuffer.wrap(nonce).getLong());
            Long previouslySeenAt = seenNonces.get(key);
            if (previouslySeenAt != null && (now - previouslySeenAt) <= NONCE_REPLAY_WINDOW_MS) {
                throw new ChannelMessageException("nonce replay detected");
            }
            seenNonces.put(key, now);
        }
        byte[] msgBody = new byte[bodyLen];
        System.arraycopy(frame, HEADER_LENGTH, msgBody, 0, bodyLen);
        return new ChannelMessage(type, uuid, nonce, timestampMs, msgBody);
    }

    public byte[] freshNonce() {
        byte[] n = new byte[8];
        random.nextBytes(n);
        return n;
    }

    public long now() {
        return clock.getAsLong();
    }

    private record NonceKey(UUID uuid, long nonceLong) {}

    private static final class NonceLru extends LinkedHashMap<NonceKey, Long> {
        private static final long serialVersionUID = 1L;
        private final int max;

        NonceLru(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<NonceKey, Long> eldest) {
            return size() > max;
        }
    }
}
