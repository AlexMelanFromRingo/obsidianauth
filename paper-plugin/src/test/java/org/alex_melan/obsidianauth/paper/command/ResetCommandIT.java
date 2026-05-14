package org.alex_melan.obsidianauth.paper.command;

import static org.assertj.core.api.Assertions.assertThat;

import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.enrollment.EnrollmentHarness;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * US2 integration test for {@code /2fa-admin reset <player>}.
 *
 * <p>Covers: (a) console-invoked reset of an enrolled player, (b) refusal for an
 * unprivileged invoker, (c) idempotent no-op on a non-enrolled target, (d) admin
 * self-reset while authed leaves the current session intact.
 */
class ResetCommandIT extends IntegrationTestBase {

    private TwoFaAdminCommand command(EnrollmentHarness h) {
        return new TwoFaAdminCommand(
                h.exec, h.exec, new Permissions(h.config),
                h.enrollmentDao, h.auditChain, h.registry,
                null, h.configReloader, h.keyMigrationService,
                Logger.getLogger("ResetCommandIT"));
    }

    @Test
    void consoleReset_ofEnrolledPlayer_deletesRowAndAudits(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock player = server.addPlayer();
            UUID uuid = player.getUniqueId();
            PaperSession session = new PaperSession(
                    uuid, player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);
            h.orchestrator.startEnrollment(player, session).join();
            assertThat(h.enrollmentDao.findByPlayerUuid(uuid).join()).isPresent();

            ConsoleCommandSenderMock console = server.getConsoleSender();
            command(h).onCommand(console, null, "2fa-admin", new String[] {"reset", player.getName()});

            assertThat(h.enrollmentDao.findByPlayerUuid(uuid).join()).isEmpty();
            String auditLog = Files.readString(tmp.resolve("audit.log"));
            assertThat(auditLog).contains("\"event\":\"ADMIN_RESET\"");
            assertThat(auditLog).contains("\"outcome\":\"ok\"");
        }
    }

    @Test
    void unprivilegedInvoker_isRefusedAndChangesNothing(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock enrolled = server.addPlayer();
            UUID uuid = enrolled.getUniqueId();
            PaperSession session = new PaperSession(
                    uuid, enrolled.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);
            h.orchestrator.startEnrollment(enrolled, session).join();

            // A second, unprivileged player tries to reset the enrolled one.
            PlayerMock attacker = server.addPlayer();
            assertThat(attacker.hasPermission("totp.admin.reset")).isFalse();
            command(h).onCommand(attacker, null, "2fa-admin",
                    new String[] {"reset", enrolled.getName()});

            // Refused: the enrollment row is untouched.
            assertThat(h.enrollmentDao.findByPlayerUuid(uuid).join()).isPresent();
        }
    }

    @Test
    void resetOfNonEnrolledTarget_isIdempotentNoop(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock target = server.addPlayer();
            assertThat(h.enrollmentDao.findByPlayerUuid(target.getUniqueId()).join()).isEmpty();

            ConsoleCommandSenderMock console = server.getConsoleSender();
            command(h).onCommand(console, null, "2fa-admin", new String[] {"reset", target.getName()});

            // Still no row, and the audit log records the no-op.
            assertThat(h.enrollmentDao.findByPlayerUuid(target.getUniqueId()).join()).isEmpty();
            String auditLog = Files.readString(tmp.resolve("audit.log"));
            assertThat(auditLog).contains("\"event\":\"ADMIN_RESET\"");
            assertThat(auditLog).contains("\"outcome\":\"noop\"");
        }
    }

    @Test
    void adminSelfReset_whileAuthed_retainsCurrentSession(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp)) {
            PlayerMock admin = server.addPlayer();
            admin.setOp(true);
            UUID uuid = admin.getUniqueId();

            PaperSession session = new PaperSession(
                    uuid, admin.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);
            h.orchestrator.startEnrollment(admin, session).join();
            // Simulate the admin having already authenticated this session.
            session.setState(PaperSession.State.AUTHED);

            command(h).onCommand(admin, null, "2fa-admin", new String[] {"reset", admin.getName()});

            // The enrollment row is gone — the next join will be a fresh enrollment...
            assertThat(h.enrollmentDao.findByPlayerUuid(uuid).join()).isEmpty();
            // ...but the CURRENT session is allowed to finish (admin self-reset edge case).
            assertThat(session.state()).isEqualTo(PaperSession.State.AUTHED);
        }
    }
}
