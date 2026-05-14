package org.alex_melan.obsidianauth.paper.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.file.Files;
import java.nio.file.Path;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * US1 end-to-end: a fresh player joins, the QR card lands in the first empty hotbar slot,
 * the player types the correct TOTP code in chat, and the session transitions to
 * {@code AUTHED}.
 */
class EnrollmentFlowIT extends IntegrationTestBase {

    @Test
    void freshPlayer_enrollsThenAuthenticates(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock player = server.addPlayer();
            PaperSession session = new PaperSession(
                    player.getUniqueId(), player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);

            // --- Join → enrollment ---
            h.orchestrator.startEnrollment(player, session).join();

            // The QR card is delivered into the first empty hotbar slot (slot 0).
            assertThat(player.getInventory().getItem(0)).isNotNull();
            assertThat(player.getInventory().getItem(0).getType()).isEqualTo(Material.FILLED_MAP);
            assertThat(session.state()).isEqualTo(PaperSession.State.LOCKED_AWAITING_CODE);
            // A fresh, empty inventory means nothing was displaced — no stash file.
            assertThat(Files.exists(h.stashDir.resolve(player.getUniqueId() + ".stash"))).isFalse();

            // --- Type the correct code in chat ---
            String code = h.currentCodeFor(player.getUniqueId());
            h.verification.verify(player, session, code).join();

            // Freedom restored.
            assertThat(session.state()).isEqualTo(PaperSession.State.AUTHED);
            assertThat(session.pendingVerification()).isFalse();
        }
    }

    @Test
    void malformedCode_leavesPlayerLocked(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock player = server.addPlayer();
            PaperSession session = new PaperSession(
                    player.getUniqueId(), player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);
            h.orchestrator.startEnrollment(player, session).join();

            // A code of the wrong length cannot match any step — failure-closed, stays locked.
            h.verification.verify(player, session, "12345").join();

            assertThat(session.state()).isEqualTo(PaperSession.State.LOCKED_AWAITING_CODE);
        }
    }
}
