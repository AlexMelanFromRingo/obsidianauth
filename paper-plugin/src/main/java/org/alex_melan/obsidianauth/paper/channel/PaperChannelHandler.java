package org.alex_melan.obsidianauth.paper.channel;

import java.security.SecureRandom;
import java.util.Map;
import java.util.logging.Logger;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.audit.AuditEntry;
import org.alex_melan.obsidianauth.core.channel.AuthState;
import org.alex_melan.obsidianauth.core.channel.ChannelCodec;
import org.alex_melan.obsidianauth.core.channel.ChannelId;
import org.alex_melan.obsidianauth.core.channel.ChannelMessage;
import org.alex_melan.obsidianauth.core.channel.MessageType;
import org.alex_melan.obsidianauth.core.channel.messages.LoginGateResponse;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Paper-side handler for {@code alex_melan:obsidianauth/v1} plugin messages.
 *
 * <p>{@code onPluginMessageReceived} fires on the netty I/O thread; per the async invariant
 * the raw frame is captured and the codec work (HMAC verify, decode) dispatched to the
 * {@link AsyncExecutor} immediately.
 *
 * <p>Paper is the authority. It RECEIVES {@link MessageType#GATE_REQUEST} from Velocity and
 * replies with a {@link MessageType#GATE_RESPONSE} carrying the player's current
 * {@link AuthState}. {@code GATE_RESPONSE} and {@code INVALIDATE} are Velocity-bound — Paper
 * drops them if it somehow receives them.
 */
public final class PaperChannelHandler implements PluginMessageListener {

    private final ChannelCodec codec;
    private final byte[] hmacSecret;
    private final AsyncExecutor async;
    private final SyncExecutor sync;
    private final SessionRegistry sessions;
    private final AuditChain audit;
    private final Plugin plugin;
    private final Logger log;
    private final SecureRandom random = new SecureRandom();

    public PaperChannelHandler(ChannelCodec codec, byte[] hmacSecret,
                               AsyncExecutor async, SyncExecutor sync,
                               SessionRegistry sessions, AuditChain audit,
                               Plugin plugin, Logger log) {
        this.codec = codec;
        this.hmacSecret = hmacSecret;
        this.async = async;
        this.sync = sync;
        this.sessions = sessions;
        this.audit = audit;
        this.plugin = plugin;
        this.log = log;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel,
                                        @NotNull Player player,
                                        byte @NotNull [] message) {
        if (!ChannelId.ID.equals(channel)) return;
        codec.decodeAsync(message, hmacSecret).whenComplete((msg, err) -> {
            if (err != null) {
                log.fine("dropping malformed/forged channel frame from "
                        + player.getName() + ": " + err.getMessage());
                audit.append(new AuditEntry(
                        System.currentTimeMillis(),
                        AuditEntry.EventType.CHANNEL_HMAC_FAIL,
                        AuditEntry.Actor.system(),
                        player.getUniqueId(),
                        AuditEntry.Outcome.FAIL,
                        Map.of("reason", String.valueOf(err.getMessage()))));
                return;
            }
            switch (msg.type()) {
                case GATE_REQUEST  -> handleGateRequest(player, msg);
                case GATE_RESPONSE -> { /* Velocity-bound; Paper drops. */ }
                case INVALIDATE    -> { /* Velocity-bound; Paper drops. */ }
            }
        });
    }

    private void handleGateRequest(Player player, ChannelMessage request) {
        // Session-state read must happen on the main / region thread (SessionRegistry is
        // safe to read from any thread, but we keep the discipline consistent).
        sync.postToMainThread(() -> {
            PaperSession.State state = sessions.stateOf(request.playerUuid());
            AuthState authState = mapState(state);
            byte[] token = new byte[16];
            random.nextBytes(token);
            byte[] body = new LoginGateResponse(authState, token).encode();
            ChannelMessage response = new ChannelMessage(
                    MessageType.GATE_RESPONSE, request.playerUuid(),
                    codec.freshNonce(), codec.now(), body);
            // Encode (HMAC sign) on the async pool, then send back on the main thread.
            codec.encodeAsync(response, hmacSecret).whenComplete((frame, err) -> {
                if (err != null) {
                    log.warning("failed to encode GATE_RESPONSE: " + err.getMessage());
                    return;
                }
                sync.postToMainThread(() -> {
                    if (player.isOnline()) {
                        player.sendPluginMessage(plugin, ChannelId.ID, frame);
                    }
                });
            });
        });
    }

    /**
     * Broadcast an {@code AUTH_STATE_INVALIDATE} to Velocity through the given player's
     * connection. Called on logout / kick / admin reset.
     */
    public void broadcastInvalidate(Player player,
                                    org.alex_melan.obsidianauth.core.channel.messages.AuthStateInvalidate.Reason reason) {
        byte[] body = new org.alex_melan.obsidianauth.core.channel.messages.AuthStateInvalidate(reason).encode();
        ChannelMessage msg = new ChannelMessage(
                MessageType.INVALIDATE, player.getUniqueId(),
                codec.freshNonce(), codec.now(), body);
        codec.encodeAsync(msg, hmacSecret).whenComplete((frame, err) -> {
            if (err != null) {
                log.warning("failed to encode INVALIDATE: " + err.getMessage());
                return;
            }
            sync.postToMainThread(() -> {
                if (player.isOnline()) {
                    player.sendPluginMessage(plugin, ChannelId.ID, frame);
                }
            });
        });
    }

    private static AuthState mapState(PaperSession.State state) {
        if (state == null) {
            // Unknown player — failure-closed, treat as not authenticated.
            return AuthState.PENDING;
        }
        return switch (state) {
            case AUTHED               -> AuthState.AUTHED;
            case LOCKED_OUT           -> AuthState.LOCKED_OUT;
            case PENDING_ENROLLMENT,
                 LOCKED_AWAITING_CODE -> AuthState.PENDING;
        };
    }
}
