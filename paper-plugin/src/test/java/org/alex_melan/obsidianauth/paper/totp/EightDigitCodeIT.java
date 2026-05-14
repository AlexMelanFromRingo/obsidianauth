package org.alex_melan.obsidianauth.paper.totp;

import static org.assertj.core.api.Assertions.assertThat;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.file.Path;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.TestConfigs;
import org.alex_melan.obsidianauth.paper.enrollment.EnrollmentHarness;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * US3: with {@code totp.digits = 8} the provisioning URI declares {@code digits=8}, an
 * 8-digit code from the authenticator app is accepted, and a 6-digit code is rejected.
 */
class EightDigitCodeIT extends IntegrationTestBase {

    @Test
    void eightDigitConfig_flowsThroughUriAndVerification(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp, TestConfigs.totp(8, 1, "Minecraft"))) {
            PlayerMock player = server.addPlayer();
            UUID uuid = player.getUniqueId();
            PaperSession session = new PaperSession(
                    uuid, player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);
            h.orchestrator.startEnrollment(player, session).join();

            // The provisioning URI declares the configured digit count.
            StoredEnrollment record = h.enrollmentDao.findByPlayerUuid(uuid).join().orElseThrow();
            byte[] secret = h.sealer.open(
                    new AesGcmSealer.Sealed(record.ciphertext(), record.nonce(), record.authTag()),
                    h.key, uuid);
            String uri = h.orchestrator.uriFor(player, secret).orElseThrow();
            assertThat(uri).contains("digits=8");

            // A 6-digit code cannot match an 8-digit configuration — player stays locked.
            h.verification.verify(player, session, "123456").join();
            assertThat(session.state()).isEqualTo(PaperSession.State.LOCKED_AWAITING_CODE);

            // The correct 8-digit code is accepted.
            String code = h.currentCodeFor(uuid);
            assertThat(code).hasSize(8);
            h.verification.verify(player, session, code).join();
            assertThat(session.state()).isEqualTo(PaperSession.State.AUTHED);
        }
    }
}
