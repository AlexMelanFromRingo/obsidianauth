package org.alex_melan.obsidianauth.paper;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.crypto.HmacAuthenticator;
import org.alex_melan.obsidianauth.core.crypto.KeyMaterial;
import org.alex_melan.obsidianauth.core.crypto.KeyResolver;
import org.alex_melan.obsidianauth.core.ratelimit.AttemptLimiter;
import org.alex_melan.obsidianauth.core.storage.Dialect;
import org.alex_melan.obsidianauth.core.storage.JdbcAuditDao;
import org.alex_melan.obsidianauth.core.storage.JdbcEnrollmentDao;
import org.alex_melan.obsidianauth.core.storage.MigrationRunner;
import org.alex_melan.obsidianauth.paper.async.PlatformProbe;
import org.alex_melan.obsidianauth.paper.channel.PaperChannelHandler;
import org.alex_melan.obsidianauth.paper.command.Permissions;
import org.alex_melan.obsidianauth.paper.command.TwoFaAdminCommand;
import org.alex_melan.obsidianauth.paper.keyrotation.KeyMigrationService;
import org.alex_melan.obsidianauth.paper.config.LiveConfig;
import org.alex_melan.obsidianauth.paper.config.PaperConfigLoader;
import org.alex_melan.obsidianauth.paper.config.PaperConfigReloader;
import org.alex_melan.obsidianauth.paper.enrollment.CardDeliveryService;
import org.alex_melan.obsidianauth.paper.enrollment.EnrollmentOrchestrator;
import org.alex_melan.obsidianauth.paper.enrollment.SlotBorrowStash;
import org.alex_melan.obsidianauth.paper.listeners.JoinQuitListener;
import org.alex_melan.obsidianauth.paper.listeners.PreAuthChatListener;
import org.alex_melan.obsidianauth.paper.listeners.PreAuthCommandListener;
import org.alex_melan.obsidianauth.paper.listeners.PreAuthInteractionListener;
import org.alex_melan.obsidianauth.paper.listeners.PreAuthInventoryListener;
import org.alex_melan.obsidianauth.paper.listeners.PreAuthMovementListener;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.alex_melan.obsidianauth.paper.verification.ChatVerificationService;
import org.alex_melan.obsidianauth.core.channel.ChannelCodec;
import org.alex_melan.obsidianauth.core.channel.ChannelId;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper-side entry point.
 *
 * <p>{@code onEnable} performs only wiring — it constructs and holds references to every
 * service the plugin will need at runtime, but does not register any listeners or commands.
 * Listener registration is performed by per-user-story modules in later phases. This keeps
 * the foundational wiring testable in isolation and avoids partial-listener states if any
 * service fails to initialise.
 *
 * <p>Per FR-008, any failure during enable causes the plugin to refuse to load —
 * failure-closed at boot.
 */
public final class ObsidianAuthPaperPlugin extends JavaPlugin {

    private PlatformProbe.Executors executors;
    private TotpConfig config;
    private LiveConfig liveConfig;
    private HikariDataSource dataSource;
    private JdbcEnrollmentDao enrollmentDao;
    private JdbcAuditDao auditDao;
    private AuditChain auditChain;
    private AttemptLimiter rateLimiter;
    private KeyMaterial activeKey;
    private AesGcmSealer sealer;
    private byte[] channelHmacSecret;
    private ChannelCodec channelCodec;
    private SessionRegistry sessionRegistry;
    private SlotBorrowStash slotBorrowStash;
    private CardDeliveryService cardDeliveryService;
    private EnrollmentOrchestrator enrollmentOrchestrator;
    private ChatVerificationService chatVerificationService;
    private PaperChannelHandler channelHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            wire();
            getLogger().info("ObsidianAuth enabled. Platform=" + executors.platformLabel()
                    + ", key_version=" + activeKey.version()
                    + ", backend=" + getConfig().getString("storage.backend", "sqlite"));
        } catch (Exception e) {
            getLogger().severe("ObsidianAuth FAILED to enable: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (executors != null) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
            executors.async().shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        if (activeKey != null) {
            activeKey.wipe();
        }
    }

    private void wire() throws Exception {
        // Resolve platform-specific executors (Bukkit vs Folia detected at runtime).
        executors = PlatformProbe.resolve(this);
        AsyncExecutor async = executors.async();
        SyncExecutor sync = executors.sync();
        getLogger().info("Async executor selected: " + executors.platformLabel());

        // Load and validate config — refuses to enable on any rule violation (FR-025).
        config = PaperConfigLoader.load(getConfig());
        liveConfig = new LiveConfig(config);

        // Resolve the AES master key (KMS > file > env precedence, FR-019).
        String backend = getConfig().getString("storage.backend", "sqlite");
        String kmsRef = getConfig().getString("encryption.kms.reference", "");
        String fileRefRaw = getConfig().getString("encryption.file.path", "");
        Path fileRef = fileRefRaw.isBlank() ? null : Path.of(fileRefRaw);
        String envVar = getConfig().getString("encryption.env.variable", "OBSIDIANAUTH_MASTER_KEY");
        KeyResolver keyResolver = new KeyResolver(async, null, kmsRef, fileRef, envVar, 1);
        // Plugin bootstrap is the only place where blocking the server-startup thread on a
        // CompletableFuture is permitted (the server itself is single-threaded here and the
        // failure-closed contract requires the key BEFORE any listener registers).
        activeKey = keyResolver.resolve().join();
        sealer = new AesGcmSealer();

        // Open the JDBC pool. Validate password source first so misconfig fails-fast.
        dataSource = openDataSource(backend);

        // Run Flyway migrations on the async pool.
        Dialect dialect = Dialect.fromString(backend);
        int migrated = new MigrationRunner(async, dataSource, dialect).migrate().join();
        getLogger().info("Flyway migrations applied: " + migrated);

        // Wire DAOs, audit chain, and rate limiter.
        enrollmentDao = new JdbcEnrollmentDao(dataSource, async);
        auditDao = new JdbcAuditDao(dataSource, async);
        Path auditFile = Path.of(getConfig().getString("audit.file", "plugins/ObsidianAuth/audit.log"));
        auditChain = new AuditChain(async, auditFile, auditDao);
        auditChain.loadHead().join();
        // Startup tamper check (FR-008): a mismatch between the DB audit head and the
        // audit.log tail throws AuditTamperException, which the onEnable catch turns into a
        // SEVERE log line and a refusal to enable.
        auditChain.verifyOnStartup().join();

        rateLimiter = new AttemptLimiter(
                config.rateLimitMaxFailures(),
                config.rateLimitWindowSeconds());

        // Session registry — created early so the channel handler can consult it.
        sessionRegistry = new SessionRegistry();

        // Plugin-message channel (only if proxy mode is enabled).
        if (config.proxyChannelEnabled()) {
            channelHmacSecret = resolveChannelHmacSecret();
            channelCodec = new ChannelCodec(async);
            channelHandler = new PaperChannelHandler(
                    channelCodec, channelHmacSecret, async, sync,
                    sessionRegistry, auditChain, this, getLogger());
            getServer().getMessenger().registerIncomingPluginChannel(this, ChannelId.ID, channelHandler);
            getServer().getMessenger().registerOutgoingPluginChannel(this, ChannelId.ID);
            getLogger().info("Plugin-message channel '" + ChannelId.ID + "' registered.");
        }

        // Enrollment + verification services.
        Path stashDir = Path.of(getConfig().getString("audit.file", "plugins/ObsidianAuth/audit.log"))
                .getParent().resolve("stash");
        slotBorrowStash = new SlotBorrowStash(async, stashDir);
        cardDeliveryService = new CardDeliveryService(async, sync, slotBorrowStash);
        enrollmentOrchestrator = new EnrollmentOrchestrator(
                sealer, activeKey, enrollmentDao, auditChain, liveConfig, cardDeliveryService, async, sync);
        chatVerificationService = new ChatVerificationService(
                enrollmentDao, sealer, activeKey, liveConfig, auditChain, rateLimiter,
                cardDeliveryService, async, sync, getLogger());

        // Lockdown listener matrix (FR-006 / FR-007).
        var pm = getServer().getPluginManager();
        pm.registerEvents(new JoinQuitListener(
                async, sessionRegistry, enrollmentDao, enrollmentOrchestrator, cardDeliveryService), this);
        pm.registerEvents(new PreAuthMovementListener(sessionRegistry), this);
        pm.registerEvents(new PreAuthInteractionListener(sessionRegistry), this);
        pm.registerEvents(new PreAuthInventoryListener(sessionRegistry), this);
        pm.registerEvents(new PreAuthCommandListener(sessionRegistry), this);
        pm.registerEvents(new PreAuthChatListener(sessionRegistry, chatVerificationService, liveConfig), this);
        getLogger().info("Lockdown listener matrix registered (6 listeners).");

        // Admin command surface (/2fa-admin reset|reload|migrate-keys|migrate-cancel).
        PaperConfigReloader configReloader = new PaperConfigReloader(
                liveConfig, getConfig(),
                () -> { reloadConfig(); return getConfig(); });
        // Single-version deployment: only the active key is resolvable. Older rows (and thus
        // a non-trivial migration) cannot exist until versioned key sources land — a
        // post-MVP feature — so this provider intentionally rejects any other version.
        KeyMigrationService.KeyProvider keyProvider = version -> {
            if (version == activeKey.version()) {
                return activeKey;
            }
            throw new IllegalStateException(
                    "no key material configured for version " + version
                            + " — multi-version key sources are out of MVP scope");
        };
        KeyMigrationService keyMigrationService = new KeyMigrationService(
                enrollmentDao, sealer, keyProvider, activeKey.version(), auditChain, async);
        TwoFaAdminCommand adminCommand = new TwoFaAdminCommand(
                async, sync, new Permissions(config), enrollmentDao, auditChain,
                sessionRegistry, channelHandler, configReloader, keyMigrationService, getLogger());
        PluginCommand pc = getCommand("2fa-admin");
        if (pc == null) {
            throw new IllegalStateException("command '2fa-admin' missing from plugin.yml");
        }
        pc.setExecutor(adminCommand);
        pc.setTabCompleter(adminCommand);
        getLogger().info("Admin command '/2fa-admin' registered.");
    }

    private HikariDataSource openDataSource(String backend) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("ObsidianAuth-pool");
        cfg.setConnectionTimeout(1000);
        cfg.setValidationTimeout(250);
        cfg.setInitializationFailTimeout(0);          // failFast — refuse start on DB unreachable.
        switch (backend) {
            case "sqlite" -> {
                String file = getConfig().getString("storage.sqlite.file", "plugins/ObsidianAuth/data.db");
                cfg.setJdbcUrl("jdbc:sqlite:" + file);
                cfg.setMaximumPoolSize(1);            // SQLite single-writer.
            }
            case "mysql" -> {
                String host = getConfig().getString("storage.mysql.host", "127.0.0.1");
                int port = getConfig().getInt("storage.mysql.port", 3306);
                String db = getConfig().getString("storage.mysql.database", "obsidianauth");
                String user = getConfig().getString("storage.mysql.user", "obsidianauth_app");
                int poolMax = Math.min(getConfig().getInt("storage.mysql.pool_max_connections", 4), 16);
                cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db);
                cfg.setUsername(user);
                cfg.setPassword(resolveSecret(getConfig().getString("storage.mysql.password_source", "")));
                cfg.setMaximumPoolSize(poolMax);
            }
            default -> throw new IllegalStateException("Unknown storage.backend: " + backend);
        }
        return new HikariDataSource(cfg);
    }

    private byte[] resolveChannelHmacSecret() {
        String src = getConfig().getString("proxy_channel.hmac_secret_source", "env:OBSIDIANAUTH_CHANNEL_HMAC");
        String resolved = resolveSecret(src);
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

    private static String resolveSecret(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("secret source is empty");
        }
        if (source.startsWith("env:")) {
            String env = System.getenv(source.substring(4));
            if (env == null || env.isBlank()) {
                throw new IllegalStateException("env var " + source.substring(4) + " is not set");
            }
            return env;
        }
        if (source.startsWith("file:")) {
            try {
                return new String(java.nio.file.Files.readAllBytes(Path.of(source.substring(5)))).trim();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("failed to read secret file " + source.substring(5), e);
            }
        }
        throw new IllegalStateException(
                "secret source must be env:VAR or file:PATH; got '" + source + "'");
    }

    // --- Accessors for future listener/command classes ---

    public AsyncExecutor asyncExecutor() { return executors.async(); }
    public SyncExecutor syncExecutor() { return executors.sync(); }
    public TotpConfig totpConfig() { return config; }
    public JdbcEnrollmentDao enrollmentDao() { return enrollmentDao; }
    public AuditChain auditChain() { return auditChain; }
    public AttemptLimiter rateLimiter() { return rateLimiter; }
    public AesGcmSealer sealer() { return sealer; }
    public KeyMaterial activeKey() { return activeKey; }
    public ChannelCodec channelCodec() { return channelCodec; }
    public byte[] channelHmacSecret() { return channelHmacSecret; }
}
