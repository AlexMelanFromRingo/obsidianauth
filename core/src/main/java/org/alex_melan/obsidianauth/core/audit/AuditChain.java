package org.alex_melan.obsidianauth.core.audit;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.storage.AuditDao;
import org.alex_melan.obsidianauth.core.storage.AuditHead;

/**
 * Tamper-evident audit log. Each entry is one line of JSON in {@code audit.log}; entries
 * are chained by including the previous entry's {@code this_hash} as {@code prev_hash}.
 *
 * <p>Canonical form for hashing:
 * <ul>
 *   <li>Keys sorted alphabetically.</li>
 *   <li>{@code this_hash} omitted from the canonical bytes.</li>
 *   <li>UTF-8 encoding, no trailing newline.</li>
 *   <li>String values use minimal JSON escaping (only {@code \"}, {@code \\}, {@code \n}, {@code \r}, {@code \t}).</li>
 * </ul>
 *
 * <p>All file I/O and DB I/O is dispatched through the supplied {@link AsyncExecutor};
 * the main / region thread NEVER touches {@code FileChannel.force} or JDBC.
 */
public final class AuditChain {

    /** Sixty-four zeros — the genesis prev_hash. */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private final AsyncExecutor async;
    private final Path logFile;
    private final AuditDao auditDao;
    /** In-memory cache of the last-written head, kept consistent with the DB shadow. */
    private final AtomicReference<AuditHead> headCache = new AtomicReference<>(null);

    public AuditChain(AsyncExecutor async, Path logFile, AuditDao auditDao) {
        this.async = Objects.requireNonNull(async);
        this.logFile = Objects.requireNonNull(logFile);
        this.auditDao = Objects.requireNonNull(auditDao);
    }

    /**
     * Append one audit entry. The future completes once the line is fsync'd to the log
     * file AND the {@code audit_head} row is advanced. On any failure the future
     * completes exceptionally and the caller is responsible for surfacing the audit
     * failure (the application MUST NOT silently continue — Constitution Security).
     */
    public CompletableFuture<AuditHead> append(AuditEntry entry) {
        return async.submit(() -> appendSync(entry));
    }

    /**
     * Loads the persisted head into the in-memory cache. Call once at plugin enable
     * before the first {@link #append} call. Tamper detection (comparing the DB head's
     * {@code this_hash} against the actual tail of {@code audit.log}) is a separate
     * concern.
     */
    public CompletableFuture<Void> loadHead() {
        return async.submit((java.util.function.Supplier<Void>) () -> {
            auditDao.readHead().join().ifPresent(headCache::set);
            return null;
        });
    }

    AuditHead appendSync(AuditEntry entry) {
        AuditHead currentHead = headCache.get();
        long expectedSeq = (currentHead == null) ? 0L : currentHead.seq();
        long newSeq = expectedSeq + 1;
        String prevHash = (currentHead == null)
                ? GENESIS_PREV_HASH
                : toHex(currentHead.thisHash());

        String canonical = canonicalJson(entry, prevHash);
        byte[] thisHashBytes = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        String thisHashHex = toHex(thisHashBytes);
        String line = renderLine(entry, prevHash, thisHashHex);

        long offset = appendToLogFsynced(line);
        boolean advanced = auditDao.advanceHead(expectedSeq, newSeq, thisHashBytes, offset).join();
        if (!advanced) {
            throw new IllegalStateException(
                    "audit_head CAS failed at seq=" + expectedSeq + "; another writer raced");
        }
        AuditHead newHead = new AuditHead(newSeq, thisHashBytes, offset, System.currentTimeMillis());
        headCache.set(newHead);
        return newHead;
    }

    long appendToLogFsynced(String line) {
        try {
            Files.createDirectories(logFile.getParent());
            long offsetBefore;
            try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "rw")) {
                offsetBefore = raf.length();
                raf.seek(offsetBefore);
                byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                raf.write(bytes);
                try (FileChannel ch = raf.getChannel()) {
                    ch.force(true);
                }
            }
            return offsetBefore;
        } catch (IOException e) {
            throw new IllegalStateException("audit-log append failed: " + logFile, e);
        }
    }

    /**
     * Build the canonical form for hashing: a JSON object with alphabetically-sorted
     * keys and {@code this_hash} EXCLUDED. The same {@code prevHash} value is folded
     * into the canonical form so the chain is unbroken.
     */
    static String canonicalJson(AuditEntry entry, String prevHash) {
        Map<String, Object> map = new TreeMap<>();
        map.put("actor", entry.actor().wireRepresentation());
        if (!entry.context().isEmpty()) {
            Map<String, Object> sortedCtx = new TreeMap<>(entry.context());
            map.put("context", sortedCtx);
        }
        map.put("event", entry.event().name());
        map.put("outcome", entry.outcome().name().toLowerCase());
        map.put("prev_hash", prevHash);
        if (entry.targetUuid() != null) {
            map.put("target", entry.targetUuid().toString());
        }
        map.put("ts", entry.tsMillis());
        return writeJson(map);
    }

    /** Build the actual log line by adding {@code this_hash} to the canonical form. */
    static String renderLine(AuditEntry entry, String prevHash, String thisHashHex) {
        Map<String, Object> map = new TreeMap<>();
        map.put("actor", entry.actor().wireRepresentation());
        if (!entry.context().isEmpty()) {
            Map<String, Object> sortedCtx = new TreeMap<>(entry.context());
            map.put("context", sortedCtx);
        }
        map.put("event", entry.event().name());
        map.put("outcome", entry.outcome().name().toLowerCase());
        map.put("prev_hash", prevHash);
        if (entry.targetUuid() != null) {
            map.put("target", entry.targetUuid().toString());
        }
        map.put("this_hash", thisHashHex);
        map.put("ts", entry.tsMillis());
        return writeJson(map);
    }

    // --- tiny JSON writer (limited to the shape this class produces) ---

    @SuppressWarnings("unchecked")
    private static String writeJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeJsonInto(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJsonInto(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?,?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey().toString());
                sb.append(':');
                writeJsonInto(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof UUID) {
            writeString(sb, value.toString());
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // --- exposed for tests ---

    AuditHead headForTest() { return headCache.get(); }

    static String canonicalJsonForTest(AuditEntry entry, String prevHash) {
        return canonicalJson(entry, prevHash);
    }

    @SuppressWarnings("unused")
    private static OpenOption[] appendOpts() {
        return new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE};
    }
}
