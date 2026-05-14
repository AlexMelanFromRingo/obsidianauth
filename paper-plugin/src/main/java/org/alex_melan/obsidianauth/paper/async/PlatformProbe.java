package org.alex_melan.obsidianauth.paper.async;

import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Runtime detection of Folia vs regular Paper, and selection of the matching
 * {@link AsyncExecutor} / {@link SyncExecutor} pair.
 *
 * <p>Detection uses {@code Class.forName} against a class that exists ONLY on Folia
 * ({@code io.papermc.paper.threadedregions.RegionizedServer}). This is the
 * Paper-team-recommended pattern; it does NOT reach into server internals and so does not
 * violate Constitution Principle II.
 */
public final class PlatformProbe {

    private static final String FOLIA_SENTINEL = "io.papermc.paper.threadedregions.RegionizedServer";

    private PlatformProbe() {
        // static-only
    }

    public static boolean isFolia() {
        try {
            Class.forName(FOLIA_SENTINEL);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static Executors resolve(Plugin plugin) {
        if (isFolia()) {
            return new Executors(new FoliaAsyncExecutor(plugin), new FoliaSyncExecutor(plugin), "folia");
        }
        return new Executors(new BukkitAsyncExecutor(plugin), new BukkitSyncExecutor(plugin), "paper");
    }

    /** Resolved executor pair plus a label for the audit log. */
    public record Executors(AsyncExecutor async, SyncExecutor sync, String platformLabel) {}
}
