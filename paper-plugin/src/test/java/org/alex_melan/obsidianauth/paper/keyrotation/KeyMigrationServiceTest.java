package org.alex_melan.obsidianauth.paper.keyrotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.crypto.KeyMaterial;
import org.alex_melan.obsidianauth.core.storage.Dialect;
import org.alex_melan.obsidianauth.core.storage.JdbcAuditDao;
import org.alex_melan.obsidianauth.core.storage.JdbcEnrollmentDao;
import org.alex_melan.obsidianauth.core.storage.MigrationRunner;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.core.totp.SecretGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the {@code /2fa-admin migrate-keys} / {@code migrate-cancel} engine: paged eager
 * re-encryption, the between-pages cancellation flag, and the concurrent-invocation guard.
 */
class KeyMigrationServiceTest {

    private final ImmediateAsyncExecutor exec = new ImmediateAsyncExecutor();
    private final AesGcmSealer sealer = new AesGcmSealer();
    private final KeyMaterial keyV1 = new KeyMaterial(1, keyBytes((byte) 0x11));
    private final KeyMaterial keyV2 = new KeyMaterial(2, keyBytes((byte) 0x22));

    private HikariDataSource ds;
    private JdbcEnrollmentDao enrollmentDao;
    private AuditChain auditChain;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tmp.resolve("data.db"));
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);
        new MigrationRunner(exec, ds, Dialect.SQLITE).migrate().join();
        enrollmentDao = new JdbcEnrollmentDao(ds, exec);
        JdbcAuditDao auditDao = new JdbcAuditDao(ds, exec);
        auditChain = new AuditChain(exec, tmp.resolve("audit.log"), auditDao);
        auditChain.loadHead().join();
    }

    @AfterEach
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void migrate_reSealsOlderRowsUnderTheActiveKey() {
        UUID uuid = UUID.randomUUID();
        byte[] secret = new SecretGenerator().generate();
        insertSealed(uuid, secret, keyV1);

        KeyMigrationService service = new KeyMigrationService(
                enrollmentDao, sealer, twoVersionProvider(), 2, auditChain, exec);
        KeyMigrationService.MigrationSummary summary = service.migrate().join();

        assertThat(summary.alreadyRunning()).isFalse();
        assertThat(summary.cancelled()).isFalse();
        assertThat(summary.migrated()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(service.isRunning()).isFalse();

        // The row is now on key_version 2 and still decrypts to the original secret.
        StoredEnrollment after = enrollmentDao.findByPlayerUuid(uuid).join().orElseThrow();
        assertThat(after.keyVersion()).isEqualTo(2);
        byte[] recovered = openWith(after, keyV2, uuid);
        assertThat(recovered).containsExactly(secret);
    }

    @Test
    void migrate_withNoOlderRows_isAZeroSummaryNoOp() {
        // A row already on the active version is not "older" — nothing to do.
        insertSealed(UUID.randomUUID(), new SecretGenerator().generate(), keyV2);

        KeyMigrationService service = new KeyMigrationService(
                enrollmentDao, sealer, twoVersionProvider(), 2, auditChain, exec);
        KeyMigrationService.MigrationSummary summary = service.migrate().join();

        assertThat(summary.migrated()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.cancelled()).isFalse();
    }

    @Test
    void cancel_stopsTheBatchBetweenPages() {
        // Two pages' worth of rows (page size is 100).
        for (int i = 0; i < 120; i++) {
            insertSealed(UUID.randomUUID(), new SecretGenerator().generate(), keyV1);
        }

        AtomicReference<KeyMigrationService> holder = new AtomicReference<>();
        // Request cancellation as soon as the migration touches its first key — the current
        // page still finishes, but the next page is skipped.
        KeyMigrationService.KeyProvider cancellingProvider = version -> {
            holder.get().cancel();
            return version == 1 ? keyV1 : keyV2;
        };
        KeyMigrationService service = new KeyMigrationService(
                enrollmentDao, sealer, cancellingProvider, 2, auditChain, exec);
        holder.set(service);

        KeyMigrationService.MigrationSummary summary = service.migrate().join();

        assertThat(summary.cancelled()).isTrue();
        assertThat(summary.migrated()).isEqualTo(100);   // exactly the first page
        // The remaining rows are untouched — still on key_version 1.
        assertThat(enrollmentDao.findRecordsOlderThanKeyVersion(2, null, 1000).join()).hasSize(20);
    }

    @Test
    void migrate_refusesConcurrentInvocation() {
        insertSealed(UUID.randomUUID(), new SecretGenerator().generate(), keyV1);

        AtomicReference<KeyMigrationService> holder = new AtomicReference<>();
        AtomicReference<KeyMigrationService.MigrationSummary> nested = new AtomicReference<>();
        // While the outer migration is running, a second migrate() must be refused.
        KeyMigrationService.KeyProvider reentrantProvider = version -> {
            if (nested.get() == null) {
                nested.set(holder.get().migrate().join());
            }
            return version == 1 ? keyV1 : keyV2;
        };
        KeyMigrationService service = new KeyMigrationService(
                enrollmentDao, sealer, reentrantProvider, 2, auditChain, exec);
        holder.set(service);

        service.migrate().join();

        assertThat(nested.get()).isNotNull();
        assertThat(nested.get().alreadyRunning()).isTrue();
    }

    // --- helpers ---

    private KeyMigrationService.KeyProvider twoVersionProvider() {
        return version -> switch (version) {
            case 1 -> keyV1;
            case 2 -> keyV2;
            default -> throw new IllegalStateException("no key for version " + version);
        };
    }

    private void insertSealed(UUID uuid, byte[] secret, KeyMaterial key) {
        AesGcmSealer.Sealed sealed = sealer.seal(secret, key, uuid);
        long now = System.currentTimeMillis();
        StoredEnrollment record = new StoredEnrollment(
                uuid, sealed.ciphertext(), sealed.nonce(), sealed.authTag(),
                key.version(), now, null, null, now);
        assertThat(enrollmentDao.insert(record).join()).isTrue();
    }

    private byte[] openWith(StoredEnrollment record, KeyMaterial key, UUID uuid) {
        try {
            return sealer.open(
                    new AesGcmSealer.Sealed(record.ciphertext(), record.nonce(), record.authTag()),
                    key, uuid);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    private static byte[] keyBytes(byte fill) {
        byte[] bytes = new byte[KeyMaterial.KEY_LENGTH_BYTES];
        java.util.Arrays.fill(bytes, fill);
        return bytes;
    }
}
