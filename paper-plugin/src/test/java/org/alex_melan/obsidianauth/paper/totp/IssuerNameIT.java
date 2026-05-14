package org.alex_melan.obsidianauth.paper.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.nio.file.Path;
import java.util.UUID;
import org.alex_melan.obsidianauth.core.config.ConfigValidator;
import org.alex_melan.obsidianauth.core.config.InvalidConfigException;
import org.alex_melan.obsidianauth.core.crypto.AesGcmSealer;
import org.alex_melan.obsidianauth.core.storage.StoredEnrollment;
import org.alex_melan.obsidianauth.paper.IntegrationTestBase;
import org.alex_melan.obsidianauth.paper.TestConfigs;
import org.alex_melan.obsidianauth.paper.enrollment.EnrollmentHarness;
import org.alex_melan.obsidianauth.paper.session.PaperSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * US3: the configured {@code issuer.name} flows into the provisioning URI, and an issuer
 * containing a URI-grammar metacharacter is rejected at config-validation time (FR-025) so
 * the plugin refuses to start.
 */
class IssuerNameIT extends IntegrationTestBase {

    @Test
    void configuredIssuer_appearsInProvisioningUri(@TempDir Path tmp) throws Exception {
        try (EnrollmentHarness h = new EnrollmentHarness(tmp, TestConfigs.totp(6, 1, "ExampleNet"))) {
            PlayerMock player = server.addPlayer();
            UUID uuid = player.getUniqueId();
            PaperSession session = new PaperSession(
                    uuid, player.getLocation(), PaperSession.State.PENDING_ENROLLMENT);
            h.registry.register(session);
            h.orchestrator.startEnrollment(player, session).join();

            StoredEnrollment record = h.enrollmentDao.findByPlayerUuid(uuid).join().orElseThrow();
            byte[] secret = h.sealer.open(
                    new AesGcmSealer.Sealed(record.ciphertext(), record.nonce(), record.authTag()),
                    h.key, uuid);
            String uri = h.orchestrator.uriFor(player, secret).orElseThrow();
            assertThat(uri).contains("issuer=ExampleNet");
        }
    }

    @Test
    void issuerWithColon_isRejectedByConfigValidator() {
        // ':' collides with the otpauth URI's issuer:account separator — FR-025 refuses start.
        assertThatThrownBy(() -> ConfigValidator.validate(TestConfigs.totp(6, 1, "Bad:Name")))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("issuer.name");
    }
}
