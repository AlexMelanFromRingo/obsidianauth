package org.alex_melan.obsidianauth.paper.async;

import java.util.concurrent.Executor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Regular-Paper main-thread dispatch via {@code Bukkit.getScheduler().runTask}.
 */
public final class BukkitSyncExecutor implements SyncExecutor {

    private final Plugin plugin;
    private final Executor executor;

    public BukkitSyncExecutor(Plugin plugin) {
        this.plugin = plugin;
        this.executor = task -> Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void postToMainThread(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public Executor asExecutor() {
        return executor;
    }
}
