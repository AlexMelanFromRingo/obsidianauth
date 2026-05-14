package org.alex_melan.obsidianauth.paper.command;

import org.alex_melan.obsidianauth.core.config.TotpConfig;

/**
 * Resolved Bukkit permission-node strings for the {@code /2fa-admin} sub-commands.
 *
 * <p>The node strings are operator-configurable (see {@code permissions.*} in
 * {@code config.yml}); this class snapshots the resolved values at plugin enable so the
 * command handler never has to re-read the config on the hot path.
 */
public final class Permissions {

    private final String reset;
    private final String migrateKeys;
    private final String reload;

    public Permissions(TotpConfig config) {
        this.reset = config.resetPermissionNode();
        this.migrateKeys = config.migrateKeysPermissionNode();
        this.reload = config.reloadPermissionNode();
    }

    /** Permission node for {@code /2fa-admin reset <player>}. */
    public String reset() {
        return reset;
    }

    /** Permission node for {@code /2fa-admin migrate-keys} and {@code migrate-cancel}. */
    public String migrateKeys() {
        return migrateKeys;
    }

    /** Permission node for {@code /2fa-admin reload}. */
    public String reload() {
        return reload;
    }
}
