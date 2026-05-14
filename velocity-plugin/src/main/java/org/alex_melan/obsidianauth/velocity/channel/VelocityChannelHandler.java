package org.alex_melan.obsidianauth.velocity.channel;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.alex_melan.obsidianauth.core.channel.ChannelCodec;
import org.alex_melan.obsidianauth.core.channel.ChannelId;
import org.alex_melan.obsidianauth.core.channel.messages.AuthStateInvalidate;
import org.alex_melan.obsidianauth.core.channel.messages.LoginGateResponse;
import org.alex_melan.obsidianauth.velocity.session.VelocitySessionRegistry;
import org.slf4j.Logger;

/**
 * Velocity-side handler for {@code alex_melan:obsidianauth/v1} messages from Paper.
 *
 * <p>Decodes + HMAC-verifies each frame on the async pool (per the channel contract),
 * then routes:
 * <ul>
 *   <li>{@code GATE_RESPONSE} → {@link VelocityGateService#onGateResponse} (completes the
 *       in-flight gate future and refreshes the cache)</li>
 *   <li>{@code INVALIDATE} → {@link VelocityGateService#onInvalidate} (drops the cache)</li>
 *   <li>{@code GATE_REQUEST} → dropped (Velocity is the requester, not the responder)</li>
 * </ul>
 *
 * <p>Malformed / forged frames are silently dropped.
 */
public final class VelocityChannelHandler {

    private final ChannelCodec codec;
    private final byte[] hmacSecret;
    private final VelocityGateService gateService;
    private final VelocitySessionRegistry registry;
    private final Logger log;
    private final MinecraftChannelIdentifier channelId =
            MinecraftChannelIdentifier.from(ChannelId.ID);

    public VelocityChannelHandler(ChannelCodec codec, byte[] hmacSecret,
                                  VelocityGateService gateService,
                                  VelocitySessionRegistry registry, Logger log) {
        this.codec = codec;
        this.hmacSecret = hmacSecret;
        this.gateService = gateService;
        this.registry = registry;
        this.log = log;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(channelId.getId())) {
            return;
        }
        // Mark handled at the proxy — never relay to the other side.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        byte[] frame = event.getData();
        codec.decodeAsync(frame, hmacSecret).whenComplete((msg, err) -> {
            if (err != null) {
                log.debug("dropping malformed/forged channel frame: {}", err.getMessage());
                return;
            }
            switch (msg.type()) {
                case GATE_RESPONSE -> {
                    LoginGateResponse body = LoginGateResponse.decode(msg.body());
                    gateService.onGateResponse(msg.playerUuid(), body.state(), body.opaqueSessionToken());
                }
                case INVALIDATE -> {
                    AuthStateInvalidate body = AuthStateInvalidate.decode(msg.body());
                    log.debug("auth state invalidated for {} (reason {})",
                            msg.playerUuid(), body.reason());
                    gateService.onInvalidate(msg.playerUuid());
                }
                case GATE_REQUEST -> {
                    // Velocity is the requester, not the responder. Drop.
                }
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        registry.remove(event.getPlayer().getUniqueId());
    }
}
