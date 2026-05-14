package org.alex_melan.obsidianauth.paper.async;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Folia async dispatch via {@code Server.getAsyncScheduler().runNow}.
 *
 * <p>Selected at runtime by {@link PlatformProbe} when Folia's regionised scheduler classes are
 * present on the classpath.
 */
public final class FoliaAsyncExecutor implements AsyncExecutor {

    private final Plugin plugin;
    private final AsyncScheduler scheduler;
    private volatile boolean shutdown = false;

    public FoliaAsyncExecutor(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getServer().getAsyncScheduler();
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (shutdown) {
            future.completeExceptionally(new IllegalStateException("AsyncExecutor shut down"));
            return future;
        }
        scheduler.runNow(plugin, task -> {
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
        // Folia's AsyncScheduler does not expose a per-plugin cancel; tasks naturally drain.
        scheduler.cancelTasks(plugin);
        // Touch TimeUnit to avoid the unused import that javac otherwise complains about
        // once additional methods are added — this method may grow later.
        @SuppressWarnings("unused")
        TimeUnit ignored = TimeUnit.MILLISECONDS;
    }
}
