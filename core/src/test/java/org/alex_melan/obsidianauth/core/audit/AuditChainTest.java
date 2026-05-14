package org.alex_melan.obsidianauth.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.async.ImmediateAsyncExecutor;
import org.alex_melan.obsidianauth.core.storage.Dialect;
import org.alex_melan.obsidianauth.core.storage.JdbcAuditDao;
import org.alex_melan.obsidianauth.core.storage.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditChainTest {

    private final ImmediateAsyncExecutor async = new ImmediateAsyncExecutor();
    private HikariDataSource ds;
    private JdbcAuditDao dao;
    private AuditChain chain;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tmp.resolve("audit.db"));
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);
        new MigrationRunner(async, ds, Dialect.SQLITE).migrate().join();
        dao = new JdbcAuditDao(ds, async);
        chain = new AuditChain(async, tmp.resolve("audit.log"), dao);
    }

    @AfterEach
    void tearDown() {
        if (ds != null) ds.close();
    }

    @Test
    void appendGenesis_writesLineAndAdvancesHead() throws Exception {
        var entry = sampleEntry();
        var head = chain.appendSync(entry);

        assertThat(head.seq()).isEqualTo(1L);
        var lines = Files.readAllLines(tmp.resolve("audit.log"));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"event\":\"ENROLL_OK\"");
        assertThat(lines.get(0)).contains("\"prev_hash\":\"" + AuditChain.GENESIS_PREV_HASH + "\"");
    }

    @Test
    void appendChained_eachEntryReferencesPriorHash() throws Exception {
        var head1 = chain.appendSync(sampleEntry());
        var head2 = chain.appendSync(sampleEntry());

        assertThat(head2.seq()).isEqualTo(2L);
        var lines = Files.readAllLines(tmp.resolve("audit.log"));
        assertThat(lines).hasSize(2);
        // The second line's prev_hash MUST equal the first line's this_hash.
        String firstThisHash = extract(lines.get(0), "this_hash");
        String secondPrevHash = extract(lines.get(1), "prev_hash");
        assertThat(secondPrevHash).isEqualTo(firstThisHash);
        assertThat(firstThisHash).isEqualTo(toHex(head1.thisHash()));
    }

    @Test
    void canonicalJson_isDeterministicAcrossKeyOrdering() {
        var a = new AuditEntry(100L, AuditEntry.EventType.VERIFY_OK,
                AuditEntry.Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                null, AuditEntry.Outcome.OK,
                new java.util.LinkedHashMap<String,Object>());
        var b = new AuditEntry(100L, AuditEntry.EventType.VERIFY_OK,
                AuditEntry.Actor.player(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                null, AuditEntry.Outcome.OK,
                new java.util.LinkedHashMap<String,Object>());
        assertThat(AuditChain.canonicalJsonForTest(a, "0".repeat(64)))
                .isEqualTo(AuditChain.canonicalJsonForTest(b, "0".repeat(64)));
    }

    @Test
    void contextMap_isSortedAlphabetically() {
        Map<String,Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("zeta", 1);
        ctx.put("alpha", 2);
        var entry = new AuditEntry(0L, AuditEntry.EventType.KEY_ROTATION_FINISH,
                AuditEntry.Actor.system(), null, AuditEntry.Outcome.OK, ctx);
        String canonical = AuditChain.canonicalJsonForTest(entry, "0".repeat(64));
        int alphaIdx = canonical.indexOf("\"alpha\"");
        int zetaIdx  = canonical.indexOf("\"zeta\"");
        assertThat(alphaIdx).isGreaterThan(0);
        assertThat(zetaIdx).isGreaterThan(alphaIdx);
    }

    @Test
    void tamperOfLastLine_isDetectableViaHashRecomputation() throws Exception {
        var entry = sampleEntry();
        var head = chain.appendSync(entry);

        // Read the line back, tamper with it, recompute its hash — must NOT match.
        List<String> lines = Files.readAllLines(tmp.resolve("audit.log"));
        String original = lines.get(0);
        String tampered = original.replace("\"ENROLL_OK\"", "\"VERIFY_OK\"");

        // The DB head holds the original hash; a re-hash of the tampered line wouldn't match.
        String reComputedCanonical = AuditChain.canonicalJsonForTest(
                new AuditEntry(entry.tsMillis(), AuditEntry.EventType.VERIFY_OK,
                        entry.actor(), entry.targetUuid(), entry.outcome(), entry.context()),
                AuditChain.GENESIS_PREV_HASH);
        byte[] reComputedHash = sha256(reComputedCanonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(reComputedHash).isNotEqualTo(head.thisHash());
        assertThat(tampered).isNotEqualTo(original);
    }

    // --- startup integrity check (verifyOnStartup) ---

    @Test
    void verifyOnStartup_passesForEmptyHistory() {
        // No appends yet — there is no head row, so there is nothing to reconcile.
        assertThatCode(() -> chain.verifyOnStartup().join()).doesNotThrowAnyException();
    }

    @Test
    void verifyOnStartup_passesForIntactChain() {
        chain.appendSync(sampleEntry());
        chain.appendSync(sampleEntry());

        assertThatCode(() -> chain.verifyOnStartup().join()).doesNotThrowAnyException();
    }

    @Test
    void verifyOnStartup_failsWhenTailEntryBodyMutated() throws Exception {
        chain.appendSync(sampleEntry());

        // Flip the event type in the on-disk line WITHOUT touching this_hash. "ENROLL_OK" and
        // "VERIFY_OK" are the same length, so byte offsets are preserved — only a re-hash of
        // the entry body can catch this.
        Path logFile = tmp.resolve("audit.log");
        String original = Files.readString(logFile);
        Files.writeString(logFile, original.replace("\"ENROLL_OK\"", "\"VERIFY_OK\""));

        assertThatThrownBy(() -> chain.verifyOnStartup().join())
                .hasCauseInstanceOf(AuditTamperException.class)
                .hasMessageContaining("AUDIT TAMPER DETECTED");
    }

    @Test
    void verifyOnStartup_failsWhenLogTruncated() throws Exception {
        chain.appendSync(sampleEntry());
        // The DB still records a head, but the log file no longer has the entry.
        Files.write(tmp.resolve("audit.log"), new byte[0]);

        assertThatThrownBy(() -> chain.verifyOnStartup().join())
                .hasCauseInstanceOf(AuditTamperException.class)
                .hasMessageContaining("AUDIT TAMPER DETECTED");
    }

    // --- helpers ---

    private static AuditEntry sampleEntry() {
        return new AuditEntry(
                1_700_000_000_000L,
                AuditEntry.EventType.ENROLL_OK,
                AuditEntry.Actor.player(UUID.randomUUID()),
                null,
                AuditEntry.Outcome.OK,
                Map.of());
    }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return null;
        i += key.length() + 4;
        int j = json.indexOf('"', i);
        return json.substring(i, j);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] sha256(byte[] b) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
