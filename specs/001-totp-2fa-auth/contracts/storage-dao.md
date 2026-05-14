# Contract — Storage DAO

**Location**: `core/src/main/java/org/alex_melan/obsidianauth/core/storage/`.
**Consumer**: Paper-side `EnrollmentService` and `AuditService`. Velocity does not consume this contract (FR-007a / FR-007b).

The DAO surface is deliberately narrow: the plugin makes ≤ 4 distinct queries against the DB on the hot path. The interfaces hide the SQLite-vs-MySQL split via the `Dialect` enum and Flyway-templated migrations.

**Async invariant** (NON-NEGOTIABLE per `plan.md` §"Concurrency Model"): every method on every DAO returns a `CompletableFuture<T>` and dispatches all JDBC work to the supplied `AsyncExecutor`. There is no synchronous variant of any method. Callers MUST NOT invoke `.join()`, `.get()`, or `.getNow(...)` on the returned future from the main / region thread; the only allowed continuation pattern from a listener or command handler is `.thenAcceptAsync(continuation, syncExecutor)` to post the result back to the main / region thread.

The DAO constructor takes an explicit `AsyncExecutor` reference (no static / global default) so unit tests can inject a deterministic immediate-execution executor and integration tests can inject a single-thread pool with bounded backpressure.

---

## `EnrollmentDao`

```java
public interface EnrollmentDao {

    /**
     * Look up the enrollment record for a player.
     * Future completes with Optional.empty() if no row exists.
     *
     * Returned record is the raw stored form — caller (in EnrollmentService)
     * is responsible for invoking AesGcmSealer.open(...) with AAD =
     * playerUuid || keyVersion to recover the plaintext TOTP secret.
     *
     * The CompletableFuture completes on the AsyncExecutor's thread; the
     * caller is responsible for posting any main-thread-touching continuation
     * via SyncExecutor.
     */
    CompletableFuture<Optional<StoredEnrollment>> findByPlayerUuid(UUID playerUuid);

    /**
     * Insert a fresh enrollment. Future completes with false if a row already
     * exists for this UUID — callers MUST resolve concurrent enrollment by
     * reading first and reusing per FR-005.
     */
    CompletableFuture<Boolean> insert(StoredEnrollment record);

    /**
     * Update the last_verified_at and last_step_consumed fields. Used after
     * every successful verification (FR-013, FR-014).
     *
     * The last_step_consumed check (record.lastStepConsumed < newStep) is
     * performed by SQL via a WHERE clause so concurrent verifications cannot
     * both succeed on the same time-step.
     *
     * @return future completing with true if exactly one row was updated;
     *         false if the step was already consumed (replay).
     */
    CompletableFuture<Boolean> recordVerification(UUID playerUuid, long newStepConsumed, long verifiedAtMillis);

    /**
     * Re-seal an existing record under a new key_version. Used by the lazy
     * key-rotation path (FR-017a) and by the eager batch admin command
     * (FR-017b).
     */
    CompletableFuture<Void> rotateRecord(UUID playerUuid, StoredEnrollment reSealed);

    /**
     * Returns a future of a paged batch of records whose key_version is less
     * than `activeKeyVersion`, for the eager batch migration. The batch is
     * deliberately bounded (default 100 rows) so the migration can be
     * cooperatively cancelled by the admin command; the migration loop calls
     * this repeatedly until an empty page is returned.
     *
     * Iteration is in primary-key order. Returning a paged batch (rather than
     * a Stream<...>) is intentional under the async invariant: a Stream
     * holding an open JDBC ResultSet across CompletableFuture boundaries is
     * a footgun for resource leaks.
     */
    CompletableFuture<List<StoredEnrollment>> findRecordsOlderThanKeyVersion(
            int activeKeyVersion, UUID afterUuidExclusive, int pageSize);

    /**
     * Idempotent delete. Future completes with true if a row was deleted,
     * false if no enrollment existed.
     */
    CompletableFuture<Boolean> deleteByPlayerUuid(UUID playerUuid);
}

public record StoredEnrollment(
    UUID playerUuid,
    byte[] ciphertext,    // raw stored bytes (varies by secret length)
    byte[] nonce,         // 12 bytes
    byte[] authTag,       // 16 bytes
    int    keyVersion,
    long   enrolledAtMillis,
    Long   lastVerifiedAtMillis,        // nullable
    Long   lastStepConsumed,            // nullable
    long   createdAtMillis
) {}
```

Concurrency contract:
- `recordVerification` MUST be atomic against itself: two concurrent verifications using the same `(playerUuid, newStepConsumed)` MUST result in exactly one `true`.
- `rotateRecord` MAY race with `recordVerification`; the implementation MUST either (a) update on the same row only if `key_version = old_version` (compare-and-set in the WHERE clause), or (b) take a row-level lock.

Plaintext-secret handling:
- The `StoredEnrollment` record holds only ciphertext / nonce / tag — never plaintext.
- The plaintext is materialized only inside `EnrollmentService.verifyCode(...)`, into a `byte[]` that is zero-filled with `Arrays.fill(plain, (byte)0)` in the `finally` block (FR-018).
- The plaintext byte[] exists exclusively on the `AsyncExecutor`'s thread. The `CompletableFuture<VerificationOutcome>` returned to the listener carries only the `VerificationOutcome` enum and never any byte content. The main / region thread is structurally incapable of observing plaintext bytes.

---

## `AuditDao`

```java
public interface AuditDao {

    /**
     * Read the current head of the audit hash chain.
     * Future completes with Optional.empty() if no entries have been written
     * yet.
     */
    CompletableFuture<Optional<AuditHead>> readHead();

    /**
     * Atomically advance the head pointer after a new entry has been
     * successfully appended to audit.log AND fsync'd.
     *
     * Note: the file append + fsync MUST already have completed on the
     * AsyncExecutor before this method is called. This method's only job is
     * the DB row update; it does no file I/O itself.
     *
     * @param newSeq        the new monotonic sequence number (== oldSeq + 1
     *                      or 1 for genesis)
     * @param newThisHash   SHA-256 of the new entry's canonical bytes
     * @param newFileOffset byte offset in audit.log where the new entry starts
     * @return future completing with true if updated; false if some other
     *         writer beat us to this seq (callers MUST then re-read and retry
     *         their write).
     */
    CompletableFuture<Boolean> advanceHead(long expectedCurrentSeq, long newSeq, byte[] newThisHash, long newFileOffset);
}

public record AuditHead(long seq, byte[] thisHash, long fileOffset, long updatedAtMillis) {}
```

The audit chain's actual `prev_hash` value is taken from `AuditHead.thisHash()`; the DAO is intentionally minimal because the bulk of the audit logic (canonical JSON, hash, file append, fsync) lives in `core/audit/AuditChain.java`.

---

## `Dialect`

```java
public enum Dialect {
    SQLITE("INTEGER PRIMARY KEY AUTOINCREMENT", "INTEGER", "BLOB", true),
    MYSQL ("BIGINT  PRIMARY KEY AUTO_INCREMENT", "BIGINT",  "VARBINARY(255)", false);

    public final String pkAutoincrementClause;
    public final String bigIntTypeName;
    public final String varBinaryTypeName;
    public final boolean supportsAtomicMoveDirectoryFsync;   // POSIX-only behavior hint

    Dialect(String pk, String bigInt, String varBinary, boolean posixFsync) {
        this.pkAutoincrementClause = pk;
        this.bigIntTypeName = bigInt;
        this.varBinaryTypeName = varBinary;
        this.supportsAtomicMoveDirectoryFsync = posixFsync;
    }
}
```

Passed to Flyway as placeholders (`${pk_autoincrement}`, etc.) during migration loading.

---

## Connection-pool contract

A single `HikariDataSource` is created by `JdbcEnrollmentDao`'s constructor and shared with `JdbcAuditDao`. Pool sizing:

| Backend | Default `maximumPoolSize` | Rationale |
|---------|---------------------------|-----------|
| SQLite | `1` | SQLite's single-writer model makes a >1 pool counterproductive. The single connection uses WAL journaling for concurrent reads. |
| MySQL | `min(4, config.storage.mysql.pool_max_connections)` | Auth traffic is bursty but low-volume; a 4-connection pool comfortably handles 100 concurrent enrolled players. |

Validation timeout: 250 ms. Connection timeout: 1000 ms. The pool is created with `failFast=true`; if the DB is unreachable at plugin enable time, the plugin refuses to start (FR-008 failure-closed at boot, not at runtime).

Pool lifecycle is bound to `AsyncExecutor` lifecycle: the pool is constructed during plugin enable (on the platform's bootstrap thread, which is allowed to do this one-time work) and is closed during plugin disable, after the `AsyncExecutor` has been drained. Any in-flight `CompletableFuture<T>` returned from a DAO call MUST be cancellable; the implementation wires `JdbcEnrollmentDao` to mark its prepared statements with `setQueryTimeout(2)` (seconds) so a long-running query during shutdown cannot pin the pool open indefinitely.

---

## `AsyncExecutor` and `SyncExecutor` (in `core/async/`)

```java
public interface AsyncExecutor {
    <T> CompletableFuture<T> submit(Supplier<T> work);
    CompletableFuture<Void>  submit(Runnable work);
    void shutdown();                  // called on plugin disable
}

public interface SyncExecutor {
    /** Post a runnable to the main / region thread. Returns immediately. */
    void postToMainThread(Runnable task);
    /** Adapter so it can be passed to CompletableFuture.thenAcceptAsync(...) */
    Executor asExecutor();
}
```

DAO implementations accept `AsyncExecutor` via constructor. Service classes accept both `AsyncExecutor` and `SyncExecutor` via constructor. Listener / command handlers never reach the executors directly — they go through the service classes only.
