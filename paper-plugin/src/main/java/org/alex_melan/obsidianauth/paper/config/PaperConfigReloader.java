package org.alex_melan.obsidianauth.paper.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.alex_melan.obsidianauth.core.config.InvalidConfigException;
import org.alex_melan.obsidianauth.core.config.TotpConfig;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Backs {@code /2fa-admin reload}.
 *
 * <p>Re-reads {@code config.yml}, validates it, and — provided no <em>non-reloadable</em>
 * field changed — atomically swaps the new {@link TotpConfig} into the shared
 * {@link LiveConfig}. Non-reloadable fields ({@code storage.*}, {@code encryption.*}) are
 * snapshotted at plugin enable; a change to any of them is refused with a clear message
 * because it requires a full restart (open JDBC pools, resolved master key).
 *
 * <p>{@link #reload(FileConfiguration)} is pure logic over a {@link FileConfiguration} so
 * it is unit-testable without a running server; {@link #reloadFromDisk()} is the wiring the
 * command handler calls (it pulls a freshly re-read config off the supplied disk reader).
 */
public final class PaperConfigReloader {

    private final LiveConfig liveConfig;
    private final NonReloadableSnapshot baseline;
    private final Supplier<FileConfiguration> diskReader;

    public PaperConfigReloader(LiveConfig liveConfig,
                               FileConfiguration enableTimeYaml,
                               Supplier<FileConfiguration> diskReader) {
        this.liveConfig = Objects.requireNonNull(liveConfig, "liveConfig");
        this.baseline = NonReloadableSnapshot.from(Objects.requireNonNull(enableTimeYaml, "enableTimeYaml"));
        this.diskReader = Objects.requireNonNull(diskReader, "diskReader");
    }

    /** Re-read {@code config.yml} from disk and apply it. Dispatch this on the async pool. */
    public ReloadResult reloadFromDisk() {
        return reload(diskReader.get());
    }

    /** Apply {@code newYaml} as the live configuration, or return the reason it was refused. */
    public ReloadResult reload(FileConfiguration newYaml) {
        NonReloadableSnapshot fresh = NonReloadableSnapshot.from(newYaml);
        String changedNonReloadable = baseline.firstDifference(fresh);
        if (changedNonReloadable != null) {
            return ReloadResult.refused(changedNonReloadable);
        }
        TotpConfig newConfig;
        try {
            newConfig = PaperConfigLoader.load(newYaml);
        } catch (InvalidConfigException e) {
            return ReloadResult.invalid(e.getMessage());
        }
        List<String> changed = diffReloadable(liveConfig.current(), newConfig);
        liveConfig.set(newConfig);
        return ReloadResult.applied(changed);
    }

    /** Names the reloadable {@link TotpConfig} fields whose value differs between two configs. */
    private static List<String> diffReloadable(TotpConfig oldCfg, TotpConfig newCfg) {
        List<String> changed = new ArrayList<>();
        if (oldCfg.digits() != newCfg.digits()) {
            changed.add("totp.digits");
        }
        if (oldCfg.windowSteps() != newCfg.windowSteps()) {
            changed.add("totp.window_steps");
        }
        if (!oldCfg.algorithm().equals(newCfg.algorithm())) {
            changed.add("totp.algorithm");
        }
        if (!oldCfg.issuerName().equals(newCfg.issuerName())) {
            changed.add("issuer.name");
        }
        if (!oldCfg.accountLabelTemplate().equals(newCfg.accountLabelTemplate())) {
            changed.add("issuer.account_label");
        }
        if (oldCfg.rateLimitMaxFailures() != newCfg.rateLimitMaxFailures()) {
            changed.add("rate_limit.max_failures_per_window");
        }
        if (oldCfg.rateLimitWindowSeconds() != newCfg.rateLimitWindowSeconds()) {
            changed.add("rate_limit.window_seconds");
        }
        if (oldCfg.kickOnLockout() != newCfg.kickOnLockout()) {
            changed.add("rate_limit.kick_on_lockout");
        }
        if (oldCfg.proxyChannelTimeoutMs() != newCfg.proxyChannelTimeoutMs()) {
            changed.add("proxy_channel.response_timeout_ms");
        }
        return changed;
    }

    /** Outcome of a reload attempt, surfaced back to the invoking command sender. */
    public record ReloadResult(Kind kind, List<String> changedFields, String detail) {

        public enum Kind {
            /** Reloadable fields applied (possibly zero changes). */
            APPLIED,
            /** A non-reloadable field changed — full restart required. */
            REFUSED,
            /** The new config failed validation; the live config is untouched. */
            INVALID
        }

        public ReloadResult {
            changedFields = List.copyOf(changedFields);
        }

        static ReloadResult applied(List<String> changed) {
            return new ReloadResult(Kind.APPLIED, changed, null);
        }

        static ReloadResult refused(String nonReloadableField) {
            return new ReloadResult(Kind.REFUSED, List.of(), nonReloadableField);
        }

        static ReloadResult invalid(String validationDetail) {
            return new ReloadResult(Kind.INVALID, List.of(), validationDetail);
        }
    }

    /**
     * Immutable snapshot of every {@code storage.*} / {@code encryption.*} field. A change
     * to any of these between enable time and a reload is refused.
     */
    private record NonReloadableSnapshot(
            String storageBackend,
            String sqliteFile,
            String mysqlHost,
            int mysqlPort,
            String mysqlDatabase,
            String mysqlUser,
            String mysqlPasswordSource,
            String kmsReference,
            String filePath,
            String envVariable) {

        static NonReloadableSnapshot from(FileConfiguration yaml) {
            return new NonReloadableSnapshot(
                    yaml.getString("storage.backend", "sqlite"),
                    yaml.getString("storage.sqlite.file", "plugins/ObsidianAuth/data.db"),
                    yaml.getString("storage.mysql.host", "127.0.0.1"),
                    yaml.getInt("storage.mysql.port", 3306),
                    yaml.getString("storage.mysql.database", "obsidianauth"),
                    yaml.getString("storage.mysql.user", "obsidianauth_app"),
                    yaml.getString("storage.mysql.password_source", ""),
                    yaml.getString("encryption.kms.reference", ""),
                    yaml.getString("encryption.file.path", ""),
                    yaml.getString("encryption.env.variable", "OBSIDIANAUTH_MASTER_KEY"));
        }

        /** The config path of the first differing field, or {@code null} if none differ. */
        String firstDifference(NonReloadableSnapshot other) {
            if (!storageBackend.equals(other.storageBackend)) {
                return "storage.backend";
            }
            if (!sqliteFile.equals(other.sqliteFile)) {
                return "storage.sqlite.file";
            }
            if (!mysqlHost.equals(other.mysqlHost)) {
                return "storage.mysql.host";
            }
            if (mysqlPort != other.mysqlPort) {
                return "storage.mysql.port";
            }
            if (!mysqlDatabase.equals(other.mysqlDatabase)) {
                return "storage.mysql.database";
            }
            if (!mysqlUser.equals(other.mysqlUser)) {
                return "storage.mysql.user";
            }
            if (!mysqlPasswordSource.equals(other.mysqlPasswordSource)) {
                return "storage.mysql.password_source";
            }
            if (!kmsReference.equals(other.kmsReference)) {
                return "encryption.kms.reference";
            }
            if (!filePath.equals(other.filePath)) {
                return "encryption.file.path";
            }
            if (!envVariable.equals(other.envVariable)) {
                return "encryption.env.variable";
            }
            return null;
        }
    }
}
