# Contract — Plugin-Message Channel `alex_melan:obsidianauth/v1`

Channel name reflects the project brand **ObsidianAuth**, namespaced under the canonical `alex_melan` prefix per Constitution Principle V.

**Direction**: Bidirectional between Velocity (proxy) and Paper (backend).
**Transport**: Velocity `ChannelRegistrar` registers `alex_melan:obsidianauth/v1`; Paper `Messenger` registers the same identifier as both incoming and outgoing. Both sides exchange raw `byte[]` per message.
**Confidentiality**: None — the channel travels over the same TCP socket as the player session; assume an attacker can read it on shared/cloud hosting.
**Integrity**: HMAC-SHA256 of the entire body, computed under a shared secret resolved through `KMS > key file > env` precedence (same chain as the AES master key, but a **distinct** secret).
**Failure mode**: Any frame that fails magic check, version check, length check, or HMAC verification is silently dropped on the receiver and audit-logged on Paper as `CHANNEL_HMAC_FAIL` (FR-026).

**Async invariant** (NON-NEGOTIABLE per `plan.md` §"Concurrency Model"):
- **HMAC verification** of an inbound frame is a `Mac.doFinal(...)` call. It MUST run on the `AsyncExecutor`, never on the main / region thread. The receiving listener captures the raw frame bytes synchronously and immediately dispatches `channelCodec.decodeAsync(frame, hmacSecret)` to the async pool. The listener returns to the platform before the HMAC verification completes.
- **HMAC signing** of an outbound frame is also a `Mac.doFinal(...)` call. It MUST be dispatched to `AsyncExecutor.submit(...)` before the message is sent.
- **Receiver-side state effects** (updating `VelocitySession.lastKnownState` on Velocity, or resolving the `EnrollmentDao` lookup on Paper) follow the standard pattern: async future → continuation on `SyncExecutor` if any platform-API call is needed on the result.
- **Velocity event awaits**: when Velocity intercepts a `PlayerChatEvent` or `CommandExecuteEvent` and needs a fresh `GATE_RESPONSE`, it MUST return `EventTask.resumeWhenComplete(future)` from the listener so the proxy's event loop is not blocked. The `future` resolves on the async pool when the channel response arrives (or times out at `proxy_channel.response_timeout_ms`).

---

## Frame layout

All multi-byte integers are big-endian. Total minimum frame size = 62 bytes (header + empty body + HMAC). Maximum body size = 4096 bytes (capped at frame layer to keep listener memory bounded).

```text
Offset  Size  Field            Notes
------  ----  ---------------  ---------------------------------------------------------
 0      4     magic            ASCII "TOTP" (0x54 0x4F 0x54 0x50)
 4      1     version          uint8, currently 0x01
 5      1     message_type     uint8: 0x01 GATE_REQUEST | 0x02 GATE_RESPONSE | 0x03 INVALIDATE
 6     16     player_uuid      canonical big-endian UUID bytes
22      8     nonce            8 random bytes; replay-checked via monotonic last-seen-nonce map on receiver
30      8     timestamp_ms     int64 unix-millis at sender; frames with |skew| > 30 s are dropped
38      4     body_length      uint32, length L of body (0 ≤ L ≤ 4096)
42      L     body             message-type-specific (see below)
42+L   32     hmac             HMAC-SHA256(secret, bytes[0..(42+L-1)])
```

The receiver verifies:
1. `magic == "TOTP"` and `version == 0x01`.
2. `body_length ≤ 4096`.
3. Frame total length matches `42 + body_length + 32`.
4. HMAC matches (constant-time compare).
5. `|now_ms - timestamp_ms| ≤ 30000`.
6. `(player_uuid, nonce)` not seen in the last 60 s. The receiver keeps a bounded LRU of ≤ 4096 recent `(uuid, nonce)` pairs per direction.

Any failure: drop silently, no error response.

---

## Message types

### 0x01 — `GATE_REQUEST`

Sender: Velocity. Receiver: Paper.
Purpose: Velocity is intercepting a `PlayerChatEvent` or `CommandExecuteEvent` from a player who has just connected and wants to know if Paper considers that player authenticated.

**Body** (empty): `body_length = 0`. The player UUID and the timestamp in the header are sufficient.

Velocity buffers the chat/command event for up to `response_timeout_ms` (default 3000 ms, see `config-schema.md`) awaiting a `GATE_RESPONSE` with the same UUID. On timeout: treat as `UNKNOWN` ⇒ cancel the event.

### 0x02 — `GATE_RESPONSE`

Sender: Paper. Receiver: Velocity.

**Body**:

```text
byte[0]      auth_state (uint8: 0x00 AUTHED | 0x01 PENDING | 0x02 LOCKED_OUT)
byte[1..16]  opaque_session_token (16 random bytes issued by Paper; rotated per response;
                                  Velocity treats this as opaque)
```

Velocity stores `(auth_state, opaque_session_token, now_ms)` in its `VelocitySession` for the player and applies it to subsequent same-player events without re-requesting until either:
- the cached value is older than `response_timeout_ms`, or
- a `0x03 INVALIDATE` arrives.

### 0x03 — `INVALIDATE`

Sender: Paper. Receiver: Velocity.
Purpose: Paper has changed the player's auth state out-of-band (admin reset, lockout-on-N-failures kick, voluntary logout). Velocity must drop its cache for that player and treat the next event as if `UNKNOWN`.

**Body**:

```text
byte[0]      reason (uint8: 0x00 ADMIN_RESET | 0x01 LOCKOUT | 0x02 LOGOUT | 0xFF OTHER)
```

Velocity has no cached secret to clear — it only zeroes its `VelocitySession.lastKnownState` for the named UUID and resets `opaqueSessionToken`.

---

## Threat model and explicit non-properties

The channel **does not** provide:
- Confidentiality of the message bytes. The wire format must be safe to read by an attacker on a shared host. This is acceptable because no message carries secret material — only an auth-state enum and a Paper-issued opaque token.
- Forward secrecy. Compromise of the shared HMAC secret allows replaying past frames within their 60-second nonce window. The mitigation is the timestamp ±30 s drop and the per-UUID nonce LRU; the residual exposure is one nonce window per leaked frame, not the player's TOTP secret.

The channel **does** provide:
- Integrity: an attacker without the HMAC secret cannot forge a `GATE_RESPONSE` claiming `AUTHED` for a player who isn't.
- Replay protection: bounded by the 60-second nonce window and the ±30-second timestamp skew.
- Denial-of-service caps: ≤ 4096 bytes per frame and ≤ 4096 nonces per direction — total memory for the nonce LRU is < 200 KB.

---

## Reference Java types (in `core/channel/`)

```java
public enum MessageType { GATE_REQUEST, GATE_RESPONSE, INVALIDATE }

public enum AuthState  { AUTHED, PENDING, LOCKED_OUT, UNKNOWN }   // UNKNOWN never appears on wire

public record ChannelMessage(
    int version,
    MessageType type,
    UUID playerUuid,
    byte[] nonce,                  // 8 bytes
    long timestampMs,
    byte[] body,                   // empty / 1 byte / 17 bytes depending on type
    byte[] hmac                    // 32 bytes
) {}

public interface ChannelCodec {
    /** Encode + sign. MUST be invoked on AsyncExecutor (Mac.doFinal). */
    CompletableFuture<byte[]> encodeAsync(ChannelMessage msg, byte[] hmacSecret);
    /** Decode + verify HMAC. MUST be invoked on AsyncExecutor. */
    CompletableFuture<ChannelMessage> decodeAsync(byte[] frame, byte[] hmacSecret);
}

public final class HmacAuthenticator {
    // Sync primitives — callers MUST wrap on AsyncExecutor; never call these on the main / region thread.
    public static byte[]  sign(byte[] body, byte[] secret) { /* HMAC-SHA256 */ }
    public static boolean verifyConstantTime(byte[] body, byte[] secret, byte[] tag) { /* MessageDigest.isEqual */ }
}
```
