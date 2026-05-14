package org.alex_melan.obsidianauth.paper.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.file.Path;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * US1 / FR-005: a player who disconnects mid-enrollment and rejoins must be handed the
 * SAME secret — the stored enrollment row is reused, never regenerated.
 */
class PendingSecretReuseIT extends IntegrationTestBase {

    @Test
    void rejoinMidEnrollment_reusesStoredSecret(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock player = server.addPlayer();
            UUID uuid = player.getUniqueId();

            // --- First join: a fresh secret is generated and persisted ---
            PaperSession firstSession = new PaperSession(
                    uuid, player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(firstSession);
            h.orchestrator.startEnrollment(player, firstSession).join();

            StoredEnrollment before = h.enrollmentDao.findByPlayerUuid(uuid).join().orElseThrow();

            // --- Disconnect, then rejoin before ever verifying ---
            h.registry.remove(uuid);
            PaperSession secondSession = new PaperSession(
                    uuid, player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(secondSession);
            h.orchestrator.startEnrollment(player, secondSession).join();

            StoredEnrollment after = h.enrollmentDao.findByPlayerUuid(uuid).join().orElseThrow();

            // The row is byte-for-byte unchanged — no fresh secret was minted (FR-005).
            assertThat(after.ciphertext()).containsExactly(before.ciphertext());
            assertThat(after.nonce()).containsExactly(before.nonce());
            assertThat(after.authTag()).containsExactly(before.authTag());
            assertThat(after.keyVersion()).isEqualTo(before.keyVersion());
            assertThat(after.enrolledAtMillis()).isEqualTo(before.enrolledAtMillis());

            // ...and the secret the player would be shown on rejoin is the original secret.
            byte[] secretBefore = h.sealer.open(
                    new AesGcmSealer.Sealed(before.ciphertext(), before.nonce(), before.authTag()),
                    h.key, uuid);
            byte[] secretAfter = h.sealer.open(
                    new AesGcmSealer.Sealed(after.ciphertext(), after.nonce(), after.authTag()),
                    h.key, uuid);
            assertThat(secretAfter).containsExactly(secretBefore);

            // The rejoined session is parked awaiting a code, not re-enrolling from scratch.
            assertThat(secondSession.state()).isEqualTo(PaperSession.State.LOCKED_AWAITING_CODE);
        }
    }
}
