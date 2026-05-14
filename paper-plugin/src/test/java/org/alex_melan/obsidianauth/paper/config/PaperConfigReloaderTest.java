package org.alex_melan.obsidianauth.paper.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.alex_melan.obsidianauth.paper.TestConfigs;
import org.alex_melan.obsidianauth.paper.config.PaperConfigReloader.ReloadResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the {@code /2fa-admin reload} logic. Drives {@link PaperConfigReloader}
 * with in-memory {@link YamlConfiguration} objects — no running server required.
 */
class PaperConfigReloaderTest {

    private PaperConfigReloader reloader(LiveConfig live, YamlConfiguration baseline) {
        return new PaperConfigReloader(live, baseline, () -> baseline);
    }

    @Test
    void unchangedConfig_appliesWithNoChangedFields() {
        LiveConfig live = new LiveConfig(TestConfigs.totpDefaults());
        ReloadResult result = reloader(live, new YamlConfiguration()).reload(new YamlConfiguration());

        assertThat(result.kind()).isEqualTo(ReloadResult.Kind.APPLIED);
        assertThat(result.changedFields()).isEmpty();
    }

    @Test
    void changedReloadableField_isAppliedAndReported() {
        LiveConfig live = new LiveConfig(TestConfigs.totpDefaults());
        PaperConfigReloader reloader = reloader(live, new YamlConfiguration());

        YamlConfiguration updated = new YamlConfiguration();
        updated.set("totp.digits", 8);
        ReloadResult result = reloader.reload(updated);

        assertThat(result.kind()).isEqualTo(ReloadResult.Kind.APPLIED);
        assertThat(result.changedFields()).containsExactly("totp.digits");
        // The swap is atomic and visible — the live config now carries the new value.
        assertThat(live.current().digits()).isEqualTo(8);
    }

    @Test
    void changedNonReloadableField_isRefusedAndLiveConfigUntouched() {
        LiveConfig live = new LiveConfig(TestConfigs.totpDefaults());
        // Baseline storage.backend defaults to "sqlite".
        PaperConfigReloader reloader = reloader(live, new YamlConfiguration());

        YamlConfiguration updated = new YamlConfiguration();
        updated.set("storage.backend", "mysql");
        ReloadResult result = reloader.reload(updated);

        assertThat(result.kind()).isEqualTo(ReloadResult.Kind.REFUSED);
        assertThat(result.detail()).isEqualTo("storage.backend");
        assertThat(live.current().digits()).isEqualTo(6);
    }

    @Test
    void invalidNewConfig_isRejectedAndLiveConfigUntouched() {
        LiveConfig live = new LiveConfig(TestConfigs.totpDefaults());
        PaperConfigReloader reloader = reloader(live, new YamlConfiguration());

        YamlConfiguration updated = new YamlConfiguration();
        updated.set("issuer.name", "Bad:Name");
        ReloadResult result = reloader.reload(updated);

        assertThat(result.kind()).isEqualTo(ReloadResult.Kind.INVALID);
        assertThat(result.detail()).contains("issuer.name");
        assertThat(live.current().issuerName()).isEqualTo("Minecraft");
    }
}
