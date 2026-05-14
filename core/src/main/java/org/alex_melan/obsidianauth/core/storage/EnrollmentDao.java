package org.alex_melan.obsidianauth.core.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async DAO for the {@code enrollment} table. Every method dispatches its JDBC work to
 * the supplied {@code AsyncExecutor} — there is no synchronous variant per plan.md
 * §"Concurrency Model".
 *
 * <p>Callers MUST NOT invoke {@code .join() / .get() / .getNow(...)} on the returned
 * futures from the main / region thread. Use {@code thenAcceptAsync(continuation,
 * syncExecutor.asExecutor())} to post the continuation back.
 */
public interface EnrollmentDao {

    CompletableFuture<Optional<StoredEnrollment>> findByPlayerUuid(UUID playerUuid);

    /** Future completes with {@code true} on success, {@code false} if a row already exists. */
    CompletableFuture<Boolean> insert(StoredEnrollment record);

    /**
     * CAS-on-step update of {@code last_verified_at} and {@code last_step_consumed}.
     * Future completes with {@code true} if exactly one row was updated, {@code false} if
     * another writer beat this caller to the same time-step (replay attempt).
     */
    CompletableFuture<Boolean> recordVerification(UUID playerUuid, long newStepConsumed, long verifiedAtMillis);

    /** Re-seal an existing record under a new key version. */
    CompletableFuture<Void> rotateRecord(UUID playerUuid, StoredEnrollment reSealed);

    /**
     * Paged scan for records still on older key versions. Used by the {@code /2fa-admin
     * migrate-keys} eager batch path. Pagination is by primary-key UUID order;
     * {@code afterUuidExclusive} of {@code null} starts from the beginning.
     */
    CompletableFuture<List<StoredEnrollment>> findRecordsOlderThanKeyVersion(
            int activeKeyVersion, UUID afterUuidExclusive, int pageSize);

    /** Idempotent delete. Future completes with {@code true} if a row was removed. */
    CompletableFuture<Boolean> deleteByPlayerUuid(UUID playerUuid);
}
