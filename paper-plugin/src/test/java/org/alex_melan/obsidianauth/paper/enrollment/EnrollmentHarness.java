package org.alex_melan.obsidianauth.paper.enrollment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.crypto.KeyMaterial;
import org.alex_melan.obsidianauth.core.ratelimit.AttemptLimiter;
import org.alex_melan.obsidianauth.core.storage.Dialect;
import org.alex_melan.obsidianauth.core.storage.JdbcAuditDao;
import org.alex_melan.obsidianauth.core.storage.JdbcEnrollmentDao;
import org.alex_melan.obsidianauth.core.storage.MigrationRunner;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.core.totp.TotpGenerator;
import org.alex_melan.obsidianauth.paper.TestConfigs;
import org.alex_melan.obsidianauth.paper.config.LiveConfig;
import org.alex_melan.obsidianauth.paper.config.PaperConfigReloader;
import org.alex_melan.obsidianauth.paper.keyrotation.KeyMigrationService;
import org.alex_melan.obsidianauth.paper.session.SessionRegistry;
import org.alex_melan.obsidianauth.paper.verification.ChatVerificationService;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.logging.Logger;

/**
 * Wires the full Paper-side enrollment + verification stack against an in-memory-style
 * SQLite file and {@link ImmediateAsyncExecutor} (which runs every async/sync task on the
 * calling thread). Used by the US1 enrollment integration tests so each test reads as a
 * straight-line flow.
 *
 * <p>Not a test itself — has no {@code @Test} methods. Public so command-layer tests in a
 * sibling package can reuse the same wiring.
 */
public final class EnrollmentHarness implements AutoCloseable {

    public final ImmediateAsyncExecutor exec = new ImmediateAsyncExecutor();
    public final TotpConfig config;
    public final LiveConfig liveConfig;
    public final PaperConfigReloader configReloader;
    public final HikariDataSource dataSource;
    public final JdbcEnrollmentDao enrollmentDao;
    public final JdbcAuditDao auditDao;
    public final AuditChain auditChain;
    public final AttemptLimiter rateLimiter;
    public final AesGcmSealer sealer = new AesGcmSealer();
    public final KeyMaterial key = new KeyMaterial(1, new byte[KeyMaterial.KEY_LENGTH_BYTES]);
    public final SessionRegistry registry = new SessionRegistry();
    public final SlotBorrowStash stash;
    public final CardDeliveryService cardDelivery;
    public final EnrollmentOrchestrator orchestrator;
    public final ChatVerificationService verification;
    public final KeyMigrationService keyMigrationService;
    public final Path stashDir;

    public EnrollmentHarness(Path tmp) {
        this(tmp, TestConfigs.totpDefaults());
    }

    public EnrollmentHarness(Path tmp, TotpConfig config) {
        this.config = config;
        this.liveConfig = new LiveConfig(config);
        YamlConfiguration baselineYaml = new YamlConfiguration();
        this.configReloader = new PaperConfigReloader(liveConfig, baselineYaml, () -> baselineYaml);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tmp.resolve("data.db"));
        cfg.setMaximumPoolSize(1);
        this.dataSource = new HikariDataSource(cfg);
        new MigrationRunner(exec, dataSource, Dialect.SQLITE).migrate().join();

        this.enrollmentDao = new JdbcEnrollmentDao(dataSource, exec);
        this.auditDao = new JdbcAuditDao(dataSource, exec);
        this.auditChain = new AuditChain(exec, tmp.resolve("audit.log"), auditDao);
        this.auditChain.loadHead().join();

        this.rateLimiter = new AttemptLimiter(
                config.rateLimitMaxFailures(), config.rateLimitWindowSeconds());

        this.stashDir = tmp.resolve("stash");
        this.stash = new SlotBorrowStash(exec, stashDir);
        this.cardDelivery = new CardDeliveryService(
                exec, exec, stash, Logger.getLogger("EnrollmentHarness"));
        this.orchestrator = new EnrollmentOrchestrator(
                sealer, key, enrollmentDao, auditChain, liveConfig, cardDelivery, exec, exec);
        this.verification = new ChatVerificationService(
                enrollmentDao, sealer, key, liveConfig, auditChain, rateLimiter,
                cardDelivery, exec, exec, Logger.getLogger("EnrollmentHarness"));

        KeyMigrationService.KeyProvider keyProvider = version -> {
            if (version == key.version()) {
                return key;
            }
            throw new IllegalStateException("no test key material for version " + version);
        };
        this.keyMigrationService = new KeyMigrationService(
                enrollmentDao, sealer, keyProvider, key.version(), auditChain, exec);
    }

    /** Decrypts the stored secret and computes the TOTP code valid for the current second. */
    public String currentCodeFor(UUID playerUuid) {
        StoredEnrollment record = enrollmentDao.findByPlayerUuid(playerUuid).join().orElseThrow();
        byte[] secret = null;
        try {
            secret = sealer.open(
                    new AesGcmSealer.Sealed(record.ciphertext(), record.nonce(), record.authTag()),
                    key, playerUuid);
            long counter = TotpGenerator.counterFor(
                    System.currentTimeMillis() / 1000L, config.stepSeconds());
            return TotpGenerator.generate(
                    secret, counter, config.digits(),
                    TotpGenerator.Algorithm.fromConfigName(config.algorithm()));
        } catch (Exception e) {
            throw new IllegalStateException("could not derive current code for " + playerUuid, e);
        } finally {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
        }
    }

    @Override
    public void close() {
        dataSource.close();
        key.wipe();
    }
}
