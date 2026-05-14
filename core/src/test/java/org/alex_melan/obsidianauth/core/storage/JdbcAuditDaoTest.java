package org.alex_melan.obsidianauth.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcAuditDaoTest {

    private final ImmediateAsyncExecutor async = new ImmediateAsyncExecutor();
    private HikariDataSource ds;
    private JdbcAuditDao dao;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tmp.resolve("audit.db"));
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);
        new MigrationRunner(async, ds, Dialect.SQLITE).migrateSync();
        dao = new JdbcAuditDao(ds, async);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void readHead_emptyOnFreshSchema() {
        assertThat(dao.readHeadSync()).isEmpty();
    }

    @Test
    void advanceHead_genesisInsertSucceeds() {
        byte[] hash = sha("genesis");
        assertThat(dao.advanceHeadSync(0L, 1L, hash, 0L)).isTrue();
        Optional<AuditHead> head = dao.readHeadSync();
        assertThat(head).isPresent();
        assertThat(head.get().seq()).isEqualTo(1L);
        assertThat(head.get().thisHash()).containsExactly(hash);
    }

    @Test
    void advanceHead_subsequentSucceedsOnMatchingExpectedSeq() {
        dao.advanceHeadSync(0L, 1L, sha("g"), 0L);
        assertThat(dao.advanceHeadSync(1L, 2L, sha("h"), 128L)).isTrue();
        assertThat(dao.readHeadSync().orElseThrow().seq()).isEqualTo(2L);
    }

    @Test
    void advanceHead_rejectsStaleExpectedSeq() {
        dao.advanceHeadSync(0L, 1L, sha("g"), 0L);
        dao.advanceHeadSync(1L, 2L, sha("h"), 128L);
        // Caller still thinks the head is at seq=1 — CAS must fail.
        assertThat(dao.advanceHeadSync(1L, 3L, sha("i"), 256L)).isFalse();
        assertThat(dao.readHeadSync().orElseThrow().seq()).isEqualTo(2L);
    }

    @Test
    void advanceHead_genesisLosesRaceReturnsFalse() {
        assertThat(dao.advanceHeadSync(0L, 1L, sha("g"), 0L)).isTrue();
        assertThat(dao.advanceHeadSync(0L, 1L, sha("g"), 0L)).isFalse();
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
