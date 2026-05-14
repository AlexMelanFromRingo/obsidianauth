package org.alex_melan.obsidianauth.core.storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Async DAO for the {@code audit_head} singleton row. The actual append-and-fsync of
 * {@code audit.log} happens in {@link
 * org.alex_melan.obsidianauth.core.audit.AuditChain}; this DAO only updates the DB
 * pointer that tracks where the log's tail is.
 */
public interface AuditDao {

    CompletableFuture<Optional<AuditHead>> readHead();

    /**
     * Compare-and-set advance of the audit head. {@code expectedCurrentSeq} MUST match
     * the {@code seq} field of the current head (or be {@code 0} when writing the
     * genesis entry).
     *
     * @return future completing with {@code true} on success, {@code false} if another
     *         writer raced ahead and advanced the head.
     */
    CompletableFuture<Boolean> advanceHead(
            long expectedCurrentSeq, long newSeq, byte[] newThisHash, long newFileOffset);
}
