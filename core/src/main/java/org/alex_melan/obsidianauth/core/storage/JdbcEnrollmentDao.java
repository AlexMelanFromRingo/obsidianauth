package org.alex_melan.obsidianauth.core.storage;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;

/**
 * JDBC-backed {@link EnrollmentDao} implementation. Works against either SQLite or MySQL
 * (the chosen backend is identified by {@link Dialect} for migrations; the SQL in this
 * class is portable between the two).
 *
 * <p>Every public method dispatches its work onto the supplied {@link AsyncExecutor};
 * callers MUST NOT invoke this DAO from the main / region thread.
 */
public final class JdbcEnrollmentDao implements EnrollmentDao {

    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private static final String SQL_SELECT_BY_UUID =
            "SELECT player_uuid, ciphertext, nonce, auth_tag, key_version, enrolled_at, "
                    + "last_verified_at, last_step_consumed, created_at "
                    + "FROM enrollment WHERE player_uuid = ?";

    private static final String SQL_INSERT =
            "INSERT INTO enrollment "
                    + "(player_uuid, ciphertext, nonce, auth_tag, key_version, enrolled_at, "
                    + "last_verified_at, last_step_consumed, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // CAS on step counter — UPDATE only fires if last_step_consumed is NULL or strictly less
    // than the new step. This is the core of the FR-014 replay protection.
    private static final String SQL_RECORD_VERIFICATION =
            "UPDATE enrollment SET last_verified_at = ?, last_step_consumed = ? "
                    + "WHERE player_uuid = ? "
                    + "AND (last_step_consumed IS NULL OR last_step_consumed < ?)";

    // CAS on key_version — UPDATE only fires if the row is still on the OLD version.
    private static final String SQL_ROTATE =
            "UPDATE enrollment SET ciphertext = ?, nonce = ?, auth_tag = ?, key_version = ? "
                    + "WHERE player_uuid = ? AND key_version = ?";

    private static final String SQL_FIND_OLDER_PAGED_FIRST =
            "SELECT player_uuid, ciphertext, nonce, auth_tag, key_version, enrolled_at, "
                    + "last_verified_at, last_step_consumed, created_at "
                    + "FROM enrollment WHERE key_version < ? "
                    + "ORDER BY player_uuid LIMIT ?";

    private static final String SQL_FIND_OLDER_PAGED_AFTER =
            "SELECT player_uuid, ciphertext, nonce, auth_tag, key_version, enrolled_at, "
                    + "last_verified_at, last_step_consumed, created_at "
                    + "FROM enrollment WHERE key_version < ? AND player_uuid > ? "
                    + "ORDER BY player_uuid LIMIT ?";

    private static final String SQL_DELETE =
            "DELETE FROM enrollment WHERE player_uuid = ?";

    private final DataSource dataSource;
    private final AsyncExecutor async;

    public JdbcEnrollmentDao(DataSource dataSource, AsyncExecutor async) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.async = Objects.requireNonNull(async);
    }

    @Override
    public CompletableFuture<Optional<StoredEnrollment>> findByPlayerUuid(UUID playerUuid) {
        return async.submit(() -> findByPlayerUuidSync(playerUuid));
    }

    @Override
    public CompletableFuture<Boolean> insert(StoredEnrollment record) {
        return async.submit(() -> insertSync(record));
    }

    @Override
    public CompletableFuture<Boolean> recordVerification(
            UUID playerUuid, long newStepConsumed, long verifiedAtMillis) {
        return async.submit(() -> recordVerificationSync(playerUuid, newStepConsumed, verifiedAtMillis));
    }

    @Override
    public CompletableFuture<Void> rotateRecord(UUID playerUuid, StoredEnrollment reSealed) {
        return async.submit((java.util.function.Supplier<Void>) () -> {
            rotateRecordSync(playerUuid, reSealed);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<StoredEnrollment>> findRecordsOlderThanKeyVersion(
            int activeKeyVersion, UUID afterUuidExclusive, int pageSize) {
        return async.submit(() -> findRecordsOlderThanKeyVersionSync(activeKeyVersion, afterUuidExclusive, pageSize));
    }

    @Override
    public CompletableFuture<Boolean> deleteByPlayerUuid(UUID playerUuid) {
        return async.submit(() -> deleteByPlayerUuidSync(playerUuid));
    }

    // --- synchronous helpers (must NEVER be called from the main thread) ----------------------

    Optional<StoredEnrollment> findByPlayerUuidSync(UUID playerUuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_BY_UUID)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setBytes(1, uuidToBytes(playerUuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readRow(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("findByPlayerUuid failed", e);
        }
    }

    boolean insertSync(StoredEnrollment record) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setBytes(1, uuidToBytes(record.playerUuid()));
            ps.setBytes(2, record.ciphertext());
            ps.setBytes(3, record.nonce());
            ps.setBytes(4, record.authTag());
            ps.setInt(5, record.keyVersion());
            ps.setLong(6, record.enrolledAtMillis());
            if (record.lastVerifiedAtMillis() == null) ps.setNull(7, java.sql.Types.BIGINT);
            else                                       ps.setLong(7, record.lastVerifiedAtMillis());
            if (record.lastStepConsumed() == null)     ps.setNull(8, java.sql.Types.BIGINT);
            else                                       ps.setLong(8, record.lastStepConsumed());
            ps.setLong(9, record.createdAtMillis());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            // Treat a unique / PK violation as "already exists" rather than a hard error.
            if (isUniqueViolation(e)) return false;
            throw new StorageException("insert failed", e);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        // ANSI SQLSTATE 23000 (and family) is the standard for integrity-constraint violations.
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) return true;
        // SQLite-JDBC (xerial) returns null SQLState; the error code is the SQLITE base
        // result code. 19 = SQLITE_CONSTRAINT family.
        if (e.getErrorCode() == 19) return true;
        // Last-resort message check — covers older drivers and embedded snapshots.
        String msg = e.getMessage();
        return msg != null && (msg.contains("UNIQUE") || msg.contains("PRIMARY KEY"));
    }

    boolean recordVerificationSync(UUID playerUuid, long newStepConsumed, long verifiedAtMillis) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_RECORD_VERIFICATION)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setLong(1, verifiedAtMillis);
            ps.setLong(2, newStepConsumed);
            ps.setBytes(3, uuidToBytes(playerUuid));
            ps.setLong(4, newStepConsumed);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StorageException("recordVerification failed", e);
        }
    }

    void rotateRecordSync(UUID playerUuid, StoredEnrollment reSealed) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_ROTATE)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setBytes(1, reSealed.ciphertext());
            ps.setBytes(2, reSealed.nonce());
            ps.setBytes(3, reSealed.authTag());
            ps.setInt(4, reSealed.keyVersion());
            ps.setBytes(5, uuidToBytes(playerUuid));
            // The "old" key_version we're rotating FROM is the current row's key_version.
            // Callers MUST pass a reSealed record where keyVersion is the NEW one and
            // separately tell us which version to rotate from — but the simplest contract
            // is: callers read the row first, decide on the new keyVersion, and we CAS
            // on (currentVersion = reSealed.keyVersion - 1). For now, accept any prior
            // version strictly less than the new one.
            ps.setInt(6, reSealed.keyVersion() - 1);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("rotateRecord failed", e);
        }
    }

    List<StoredEnrollment> findRecordsOlderThanKeyVersionSync(
            int activeKeyVersion, UUID afterUuidExclusive, int pageSize) {
        String sql = (afterUuidExclusive == null) ? SQL_FIND_OLDER_PAGED_FIRST : SQL_FIND_OLDER_PAGED_AFTER;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setInt(1, activeKeyVersion);
            if (afterUuidExclusive == null) {
                ps.setInt(2, pageSize);
            } else {
                ps.setBytes(2, uuidToBytes(afterUuidExclusive));
                ps.setInt(3, pageSize);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<StoredEnrollment> out = new ArrayList<>();
                while (rs.next()) out.add(readRow(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new StorageException("findRecordsOlderThanKeyVersion failed", e);
        }
    }

    boolean deleteByPlayerUuidSync(UUID playerUuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setBytes(1, uuidToBytes(playerUuid));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StorageException("deleteByPlayerUuid failed", e);
        }
    }

    // --- helpers ---

    static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static StoredEnrollment readRow(ResultSet rs) throws SQLException {
        UUID uuid = bytesToUuid(rs.getBytes(1));
        byte[] ct = rs.getBytes(2);
        byte[] nonce = rs.getBytes(3);
        byte[] tag = rs.getBytes(4);
        int kv = rs.getInt(5);
        long enrolled = rs.getLong(6);
        // Nullable BIGINT columns MUST be read via getLong()+wasNull(), never (Long)getObject():
        // the SQLite driver's getObject() returns an Integer for values that fit in 32 bits
        // (a ~5.8e7 RFC 6238 step counter does), so the cast threw ClassCastException as soon
        // as a row had a non-null last_step_consumed — i.e. on the first rejoin after a
        // successful verification. getLong() always yields a long regardless of storage form.
        long lastVerifiedRaw = rs.getLong(7);
        Long lastVerified = rs.wasNull() ? null : lastVerifiedRaw;
        long lastStepRaw = rs.getLong(8);
        Long lastStep = rs.wasNull() ? null : lastStepRaw;
        long created = rs.getLong(9);
        return new StoredEnrollment(uuid, ct, nonce, tag, kv, enrolled, lastVerified, lastStep, created);
    }
}
