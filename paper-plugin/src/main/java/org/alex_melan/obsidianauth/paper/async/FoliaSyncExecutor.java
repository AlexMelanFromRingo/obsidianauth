package org.alex_melan.obsidianauth.paper.async;

import java.util.concurrent.Executor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Folia sync dispatch.
 *
 * <p>By default this uses {@code GlobalRegionScheduler}; callers that have a player or
 * location in scope and want region-local execution can pass them in via the dedicated
 * overload (added later when listeners need it).
 */
public final class FoliaSyncExecutor implements SyncExecutor {

    private final Plugin plugin;
    private final Executor executor;

    public FoliaSyncExecutor(Plugin plugin) {
        this.plugin = plugin;
        this.executor = task ->
                plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    @Override
    public void postToMainThread(Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    @Override
    public Executor asExecutor() {
        return executor;
    }
}
