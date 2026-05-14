package org.alex_melan.obsidianauth.paper.async;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Regular-Paper async dispatch via {@code Bukkit.getScheduler().runTaskAsynchronously}.
 *
 * <p>Used on non-Folia Paper servers (the platform probe selects this implementation when
 * {@code io.papermc.paper.threadedregions.RegionizedServer} is absent at runtime).
 */
public final class BukkitAsyncExecutor implements AsyncExecutor {

    private final Plugin plugin;
    private volatile boolean shutdown = false;

    public BukkitAsyncExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (shutdown) {
            future.completeExceptionally(new IllegalStateException("AsyncExecutor shut down"));
            return future;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> submit(Runnable work) {
        return submit(() -> {
            work.run();
            return null;
        });
    }

    @Override
    public void shutdown() {
        shutdown = true;
        Bukkit.getScheduler().cancelTasks(plugin);
    }
}
