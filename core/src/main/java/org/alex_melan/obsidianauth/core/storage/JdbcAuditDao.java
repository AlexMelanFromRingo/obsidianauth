package org.alex_melan.obsidianauth.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;

/**
 * JDBC-backed {@link AuditDao}. The {@code audit_head} table holds a single row
 * (enforced by the {@code CHECK (id = 1)} clause); the row records where {@code
 * audit.log}'s tail is and what its hash is.
 */
public final class JdbcAuditDao implements AuditDao {

    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private static final String SQL_READ =
            "SELECT seq, this_hash, file_offset, updated_at FROM audit_head WHERE id = 1";

    // CAS on seq — UPDATE only fires if the current seq matches the caller's expected value.
    private static final String SQL_ADVANCE =
            "UPDATE audit_head SET seq = ?, this_hash = ?, file_offset = ?, updated_at = ? "
                    + "WHERE id = 1 AND seq = ?";

    private static final String SQL_INSERT_GENESIS =
            "INSERT INTO audit_head (id, seq, this_hash, file_offset, updated_at) "
                    + "VALUES (1, ?, ?, ?, ?)";

    private final DataSource dataSource;
    private final AsyncExecutor async;

    public JdbcAuditDao(DataSource dataSource, AsyncExecutor async) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.async = Objects.requireNonNull(async);
    }

    @Override
    public CompletableFuture<Optional<AuditHead>> readHead() {
        return async.submit(this::readHeadSync);
    }

    @Override
    public CompletableFuture<Boolean> advanceHead(
            long expectedCurrentSeq, long newSeq, byte[] newThisHash, long newFileOffset) {
        return async.submit(() -> advanceHeadSync(expectedCurrentSeq, newSeq, newThisHash, newFileOffset));
    }

    // --- sync helpers ---

    Optional<AuditHead> readHeadSync() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_READ)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new AuditHead(
                        rs.getLong(1),
                        rs.getBytes(2),
                        rs.getLong(3),
                        rs.getLong(4)));
            }
        } catch (SQLException e) {
            throw new StorageException("readHead failed", e);
        }
    }

    boolean advanceHeadSync(long expectedCurrentSeq, long newSeq, byte[] newThisHash, long newFileOffset) {
        long updatedAt = System.currentTimeMillis();
        // Genesis path: the audit_head row may not exist yet. INSERT first; on duplicate key fail,
        // fall through to UPDATE-with-CAS. Driver-portable approach: detect via expectedCurrentSeq=0.
        if (expectedCurrentSeq == 0L) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(SQL_INSERT_GENESIS)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                ps.setLong(1, newSeq);
                ps.setBytes(2, newThisHash);
                ps.setLong(3, newFileOffset);
                ps.setLong(4, updatedAt);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    // Lost the race to genesis insert.
                    return false;
                }
                throw new StorageException("advanceHead genesis insert failed", e);
            }
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_ADVANCE)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setLong(1, newSeq);
            ps.setBytes(2, newThisHash);
            ps.setLong(3, newFileOffset);
            ps.setLong(4, updatedAt);
            ps.setLong(5, expectedCurrentSeq);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StorageException("advanceHead CAS failed", e);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) return true;
        if (e.getErrorCode() == 19) return true;
        String msg = e.getMessage();
        return msg != null && (msg.contains("UNIQUE") || msg.contains("PRIMARY KEY"));
    }
}
