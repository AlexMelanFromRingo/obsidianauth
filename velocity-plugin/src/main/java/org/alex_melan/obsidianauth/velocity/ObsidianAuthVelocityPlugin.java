package org.alex_melan.obsidianauth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.channel.ChannelCodec;
import org.alex_melan.obsidianauth.velocity.async.VelocityAsyncExecutor;
import org.alex_melan.obsidianauth.velocity.async.VelocitySyncExecutor;
import org.alex_melan.obsidianauth.velocity.channel.VelocityChannelHandler;
import org.alex_melan.obsidianauth.velocity.channel.VelocityGateService;
import org.alex_melan.obsidianauth.velocity.config.VelocityConfig;
import org.alex_melan.obsidianauth.velocity.config.VelocityConfigLoader;
import org.alex_melan.obsidianauth.velocity.listeners.ProxyChatListener;
import org.alex_melan.obsidianauth.velocity.listeners.ProxyCommandListener;
import org.alex_melan.obsidianauth.velocity.listeners.ServerPreConnectListener;
import org.alex_melan.obsidianauth.velocity.session.VelocitySessionRegistry;
import org.slf4j.Logger;

/**
 * Velocity-side entry point.
 *
 * <p>Per FR-007a/b, this plugin holds NO database connection and NO AES key. Its sole
 * responsibilities are: register the proxy-side end of the {@code
 * alex_melan:obsidianauth/v1} channel, instantiate the {@link AsyncExecutor}, load
 * {@code velocity.toml}, and wire the channel handler. Listener registration is added by
 * the US1 phase.
 */
@Plugin(
        id = "obsidianauth",
        name = "ObsidianAuth",
        version = "1.0.0-SNAPSHOT",
        description = "Velocity-side helper for ObsidianAuth TOTP 2FA",
        url = "https://github.com/AlexMelanFromRingo/",
        authors = {"AlexMelan"})
public final class ObsidianAuthVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger log;
    private final Path dataDir;

    private VelocityConfig config;
    private VelocityAsyncExecutor async;
    private VelocitySyncExecutor sync;
    private byte[] channelHmacSecret;
    private ChannelCodec codec;

    @Inject
    public ObsidianAuthVelocityPlugin(ProxyServer proxy, Logger log, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.log = log;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        try {
            wire();
            log.info("ObsidianAuth-Velocity enabled. Channel registered.");
        } catch (Exception e) {
            log.error("ObsidianAuth-Velocity FAILED to enable: " + e.getMessage(), e);
        }
    }

    private void wire() throws Exception {
        Path tomlPath = dataDir.resolve("velocity.toml");
        if (!Files.exists(tomlPath)) {
            Files.createDirectories(dataDir);
            try (var defaultResource = getClass().getResourceAsStream("/velocity.toml")) {
                if (defaultResource != null) {
                    Files.copy(defaultResource, tomlPath);
                }
            }
        }
        config = VelocityConfigLoader.load(tomlPath);

        async = new VelocityAsyncExecutor(this, proxy);
        sync = new VelocitySyncExecutor(this, proxy);

        if (!config.proxyChannelEnabled()) {
            log.info("Proxy channel disabled in config; ObsidianAuth-Velocity is a pass-through.");
            return;
        }

        channelHmacSecret = resolveChannelHmacSecret();
        codec = new ChannelCodec(async);

        MinecraftChannelIdentifier id = MinecraftChannelIdentifier.from(
                org.alex_melan.obsidianauth.core.channel.ChannelId.ID);
        proxy.getChannelRegistrar().register(id);

        // Session registry + gate service correlate GATE_REQUEST / GATE_RESPONSE round-trips.
        VelocitySessionRegistry registry = new VelocitySessionRegistry();
        VelocityGateService gateService = new VelocityGateService(
                proxy, this, codec, channelHmacSecret, async, registry,
                config.responseTimeoutMs(), log);

        VelocityChannelHandler handler = new VelocityChannelHandler(
                codec, channelHmacSecret, gateService, registry, log);
        proxy.getEventManager().register(this, handler);
        proxy.getEventManager().register(this,
                new ProxyChatListener(gateService, config.blockChat()));
        proxy.getEventManager().register(this,
                new ProxyCommandListener(gateService, config.blockCommands()));
        proxy.getEventManager().register(this,
                new ServerPreConnectListener(config.failClosedRouting(), log));
        log.info("Velocity helper listeners registered (chat / command / pre-connect).");

        @SuppressWarnings("unused") VelocitySyncExecutor _s = sync;
    }

    private byte[] resolveChannelHmacSecret() {
        String src = config.hmacSecretSource();
        String resolved;
        if (src.startsWith("env:")) {
            resolved = System.getenv(src.substring(4));
            if (resolved == null || resolved.isBlank()) {
                throw new IllegalStateException("env var " + src.substring(4) + " is not set");
            }
        } else if (src.startsWith("file:")) {
            try {
                resolved = new String(Files.readAllBytes(Path.of(src.substring(5)))).trim();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("failed to read secret file " + src.substring(5), e);
            }
        } else {
            throw new IllegalStateException("unsupported secret source: " + src);
        }
        byte[] decoded;
        try {
            decoded = java.util.Base64.getDecoder().decode(resolved);
        } catch (IllegalArgumentException e) {
            decoded = resolved.getBytes();
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "proxy_channel.hmac_secret_source must resolve to >= 32 bytes; got " + decoded.length);
        }
        return decoded;
    }

    public ProxyServer proxy()                  { return proxy; }
    public Logger logger()                      { return log; }
    public VelocityConfig config()              { return config; }
    public AsyncExecutor asyncExecutor()        { return async; }
    public ChannelCodec channelCodec()          { return codec; }
    public byte[] channelHmacSecret()           { return channelHmacSecret; }
}
