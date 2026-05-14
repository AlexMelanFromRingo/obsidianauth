package org.alex_melan.obsidianauth.core.storage;

/**
 * Snapshot of the singleton {@code audit_head} row. Held in the DB so the plugin's
 * startup-time audit-tamper check can locate the expected tail of {@code audit.log} in
 * sub-millisecond time, rather than re-scanning the whole file.
 */
public record AuditHead(long seq, byte[] thisHash, long fileOffset, long updatedAtMillis) {
}
