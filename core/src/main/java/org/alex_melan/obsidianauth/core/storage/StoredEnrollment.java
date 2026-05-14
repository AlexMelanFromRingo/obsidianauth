package org.alex_melan.obsidianauth.core.storage;

import java.util.UUID;

/**
 * One row of the {@code enrollment} table. Only the player UUID is plaintext; the TOTP
 * shared secret lives in {@code ciphertext} together with the nonce, GCM auth tag, and
 * version of the AES master key that sealed the record.
 *
 * <p>Callers MUST never log this record verbatim — the ciphertext + tag pair is sufficient
 * for an attacker to mount an offline brute-force attempt against the master key.
 */
public record StoredEnrollment(
        UUID    playerUuid,
        byte[]  ciphertext,
        byte[]  nonce,
        byte[]  authTag,
        int     keyVersion,
        long    enrolledAtMillis,
        Long    lastVerifiedAtMillis,
        Long    lastStepConsumed,
        long    createdAtMillis) {
}
