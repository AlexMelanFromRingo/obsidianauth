package org.alex_melan.obsidianauth.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class JdbcEnrollmentDaoTest {

    private final ImmediateAsyncExecutor async = new ImmediateAsyncExecutor();
    private HikariDataSource ds;
    private JdbcEnrollmentDao dao;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tmp.resolve("test.db"));
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);
        new MigrationRunner(async, ds, Dialect.SQLITE).migrateSync();
        dao = new JdbcEnrollmentDao(ds, async);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void insertThenFind_roundTrip() {
        StoredEnrollment record = sampleRecord(UUID.randomUUID(), 1);
        assertThat(dao.insertSync(record)).isTrue();

        Optional<StoredEnrollment> back = dao.findByPlayerUuidSync(record.playerUuid());
        assertThat(back).isPresent();
        assertThat(back.get().keyVersion()).isEqualTo(1);
        assertThat(back.get().ciphertext()).containsExactly(record.ciphertext());
    }

    @Test
    void insert_duplicateReturnsFalse() {
        UUID uuid = UUID.randomUUID();
        assertThat(dao.insertSync(sampleRecord(uuid, 1))).isTrue();
        assertThat(dao.insertSync(sampleRecord(uuid, 1))).isFalse();
    }

    @Test
    void find_missingReturnsEmpty() {
        assertThat(dao.findByPlayerUuidSync(UUID.randomUUID())).isEmpty();
    }

    @Test
    void recordVerification_succeedsOnFreshStep() {
        UUID uuid = UUID.randomUUID();
        dao.insertSync(sampleRecord(uuid, 1));
        assertThat(dao.recordVerificationSync(uuid, 100L, 1700_000_000_000L)).isTrue();
    }

    @Test
    void recordVerification_rejectsReplayOfSameStep() {
        UUID uuid = UUID.randomUUID();
        dao.insertSync(sampleRecord(uuid, 1));
        assertThat(dao.recordVerificationSync(uuid, 100L, 1700_000_000_000L)).isTrue();
        // Second attempt at the same time-step MUST fail the CAS.
        assertThat(dao.recordVerificationSync(uuid, 100L, 1700_000_000_005L)).isFalse();
    }

    @Test
    void recordVerification_acceptsHigherStep() {
        UUID uuid = UUID.randomUUID();
        dao.insertSync(sampleRecord(uuid, 1));
        assertThat(dao.recordVerificationSync(uuid, 100L, 1700_000_000_000L)).isTrue();
        assertThat(dao.recordVerificationSync(uuid, 101L, 1700_000_030_000L)).isTrue();
    }

    @Test
    void delete_isIdempotent() {
        UUID uuid = UUID.randomUUID();
        dao.insertSync(sampleRecord(uuid, 1));
        assertThat(dao.deleteByPlayerUuidSync(uuid)).isTrue();
        assertThat(dao.deleteByPlayerUuidSync(uuid)).isFalse();
    }

    @Test
    void findRecordsOlderThanKeyVersion_pages() {
        for (int i = 0; i < 5; i++) {
            dao.insertSync(sampleRecord(UUID.randomUUID(), 1));
        }
        // Insert one already-rotated record.
        dao.insertSync(sampleRecord(UUID.randomUUID(), 2));

        var page1 = dao.findRecordsOlderThanKeyVersionSync(2, null, 3);
        assertThat(page1).hasSize(3);
        UUID lastUuid = page1.get(page1.size() - 1).playerUuid();
        var page2 = dao.findRecordsOlderThanKeyVersionSync(2, lastUuid, 3);
        assertThat(page2).hasSize(2);
    }

    private static StoredEnrollment sampleRecord(UUID uuid, int keyVersion) {
        byte[] ct = new byte[32];
        byte[] nonce = new byte[12];
        byte[] tag = new byte[16];
        new SecureRandom().nextBytes(ct);
        new SecureRandom().nextBytes(nonce);
        new SecureRandom().nextBytes(tag);
        long now = System.currentTimeMillis();
        return new StoredEnrollment(uuid, ct, nonce, tag, keyVersion, now, null, null, now);
    }
}
