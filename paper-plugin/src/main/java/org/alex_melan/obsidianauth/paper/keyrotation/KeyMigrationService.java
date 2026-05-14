package org.alex_melan.obsidianauth.paper.keyrotation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.audit.AuditChain;
import org.alex_melan.obsidianauth.core.audit.AuditEntry;
import org.alex_melan.obsidianauth.core.crypto.AesGcmAuthenticationException;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.crypto.KeyMaterial;
import org.alex_melan.obsidianauth.core.storage.EnrollmentDao;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;

/**
 * Backs {@code /2fa-admin migrate-keys} (and its companion {@code migrate-cancel}).
 *
 * <p>Eagerly re-encrypts every {@code enrollment} row whose {@code key_version} is below
 * the active version (FR-017b). The work is a sequential chain of paged
 * {@code CompletableFuture}s — one inflight DB query at a time — so the migration never
 * starves live verification traffic. A {@code volatile} cancellation flag is checked
 * between pages.
 *
 * <p>Re-sealing a row needs the OLD key (to decrypt) and the active key (to re-encrypt);
 * both are obtained from the injected {@link KeyProvider}. In a single-key deployment
 * there are no older rows, so {@link #migrate()} completes immediately with a zero summary.
 *
 * <p>The {@code rotateRecord} CAS in {@code JdbcEnrollmentDao} keys off
 * {@code key_version = newVersion - 1}, so this service supports single-version-step
 * rotation (v → v+1); rows more than one version behind are reported as failures.
 */
public final class KeyMigrationService {

    /** Resolves the {@link KeyMaterial} for a given key version. */
    public interface KeyProvider {
        KeyMaterial keyForVersion(int version);
    }

    /** Terminal result of a migration run, surfaced back to the invoking command. */
    public record MigrationSummary(
            int migrated, int failed, long elapsedMs, boolean cancelled, boolean alreadyRunning) {

        static MigrationSummary refusedAsAlreadyRunning() {
            return new MigrationSummary(0, 0, 0L, false, true);
        }
    }

    private static final int PAGE_SIZE = 100;

    private final EnrollmentDao dao;
    private final AesGcmSealer sealer;
    private final KeyProvider keyProvider;
    private final int activeKeyVersion;
    private final AuditChain audit;
    private final AsyncExecutor async;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelRequested = false;

    public KeyMigrationService(EnrollmentDao dao,
                               AesGcmSealer sealer,
                               KeyProvider keyProvider,
                               int activeKeyVersion,
                               AuditChain audit,
                               AsyncExecutor async) {
        this.dao = dao;
        this.sealer = sealer;
        this.keyProvider = keyProvider;
        this.activeKeyVersion = activeKeyVersion;
        this.audit = audit;
        this.async = async;
    }

    public int activeKeyVersion() {
        return activeKeyVersion;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Request cancellation of the in-progress migration; checked between pages. */
    public void cancel() {
        cancelRequested = true;
    }

    /**
     * Run the eager re-encryption batch. If a migration is already in progress the returned
     * future completes immediately with {@link MigrationSummary#alreadyRunning()}.
     */
    public CompletableFuture<MigrationSummary> migrate() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(MigrationSummary.refusedAsAlreadyRunning());
        }
        cancelRequested = false;
        long startedAtMillis = System.currentTimeMillis();
        int[] counters = {0, 0};   // [0] = migrated, [1] = failed
        return audit.append(new AuditEntry(
                        startedAtMillis,
                        AuditEntry.EventType.KEY_ROTATION_START,
                        AuditEntry.Actor.system(),
                        null,
                        AuditEntry.Outcome.OK,
                        Map.of("to_key_version", activeKeyVersion)))
                .thenCompose(head -> migratePage(null, counters, startedAtMillis))
                .whenComplete((summary, err) -> running.set(false));
    }

    private CompletableFuture<MigrationSummary> migratePage(
            UUID afterUuidExclusive, int[] counters, long startedAtMillis) {
        if (cancelRequested) {
            return finish(counters, startedAtMillis, true);
        }
        return dao.findRecordsOlderThanKeyVersion(activeKeyVersion, afterUuidExclusive, PAGE_SIZE)
                .thenCompose(page -> {
                    if (page.isEmpty()) {
                        return finish(counters, startedAtMillis, false);
                    }
                    UUID lastUuid = page.get(page.size() - 1).playerUuid();
                    return reSealPage(page, counters)
                            .thenCompose(ignored ->
                                    migratePage(lastUuid, counters, startedAtMillis));
                });
    }

    /**
     * Re-seal every row in {@code page} sequentially. The chain is built with an explicit
     * loop (not recursion) so it stays a single inflight operation at a time without
     * blocking — no {@code .join()} on the calling thread.
     */
    private CompletableFuture<Void> reSealPage(List<StoredEnrollment> page, int[] counters) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (StoredEnrollment record : page) {
            chain = chain.thenCompose(ignored -> reSealOne(record, counters));
        }
        return chain;
    }

    /** Re-seal one row under the active key, recording success/failure in {@code counters}. */
    private CompletableFuture<Void> reSealOne(StoredEnrollment record, int[] counters) {
        return async.submit(() -> reSealRecord(record))
                .thenCompose(updated -> dao.rotateRecord(record.playerUuid(), updated))
                .handle((ignored, err) -> {
                    // A single row failing must not abort the batch — count it and continue.
                    if (err == null) {
                        counters[0]++;
                    } else {
                        counters[1]++;
                    }
                    return null;
                });
    }

    /** Pure crypto step (runs on the {@link AsyncExecutor}): decrypt with the old key, re-seal. */
    private StoredEnrollment reSealRecord(StoredEnrollment record) {
        byte[] secret = null;
        try {
            KeyMaterial oldKey = keyProvider.keyForVersion(record.keyVersion());
            secret = sealer.open(
                    new AesGcmSealer.Sealed(record.ciphertext(), record.nonce(), record.authTag()),
                    oldKey, record.playerUuid());
            KeyMaterial newKey = keyProvider.keyForVersion(activeKeyVersion);
            AesGcmSealer.Sealed reSealed = sealer.seal(secret, newKey, record.playerUuid());
            return new StoredEnrollment(
                    record.playerUuid(),
                    reSealed.ciphertext(), reSealed.nonce(), reSealed.authTag(),
                    activeKeyVersion,
                    record.enrolledAtMillis(),
                    record.lastVerifiedAtMillis(),
                    record.lastStepConsumed(),
                    record.createdAtMillis());
        } catch (AesGcmAuthenticationException e) {
            throw new IllegalStateException("re-seal failed for " + record.playerUuid(), e);
        } finally {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
        }
    }

    private CompletableFuture<MigrationSummary> finish(
            int[] counters, long startedAtMillis, boolean cancelled) {
        long elapsedMs = System.currentTimeMillis() - startedAtMillis;
        MigrationSummary summary =
                new MigrationSummary(counters[0], counters[1], elapsedMs, cancelled, false);
        return audit.append(new AuditEntry(
                        System.currentTimeMillis(),
                        AuditEntry.EventType.KEY_ROTATION_FINISH,
                        AuditEntry.Actor.system(),
                        null,
                        cancelled ? AuditEntry.Outcome.NOOP : AuditEntry.Outcome.OK,
                        Map.of("migrated", counters[0],
                               "failed", counters[1],
                               "elapsed_ms", elapsedMs,
                               "cancelled", cancelled)))
                .thenApply(head -> summary);
    }
}
