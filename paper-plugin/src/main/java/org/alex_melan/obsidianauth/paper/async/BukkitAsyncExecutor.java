package org.alex_melan.obsidianauth.paper.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Regular-Paper async dispatch, backed by a dedicated daemon thread pool.
 *
 * <p>It deliberately does <b>not</b> use {@code Bukkit.getScheduler().runTaskAsynchronously}.
 * The Bukkit scheduler only moves queued async tasks onto its worker pool from the
 * main-thread heartbeat, and that heartbeat is not yet running during
 * {@code JavaPlugin.onEnable}. The plugin's bootstrap MUST block ({@code .join()}) on key
 * resolution, DB migrations and the audit-integrity check before any listener registers —
 * the failure-closed contract requires it — so a heartbeat-dependent executor deadlocks the
 * server at enable time (main thread blocked on a task that can never be dispatched).
 *
 * <p>A plain {@link ExecutorService} runs work on real threads immediately, independent of
 * the tick loop, while still keeping every DB / crypto / fsync operation off the main /
 * region thread (the constitutional requirement). A <em>cached</em> pool is used so the
 * nested {@code .join()} chains in the service layer — e.g. a verification task that joins a
 * DAO task that joins an audit-append task — always find a free worker and cannot
 * self-deadlock.
 */
public final class BukkitAsyncExecutor implements AsyncExecutor {

    private final ExecutorService pool;
    private volatile boolean shutdown = false;

    public BukkitAsyncExecutor(Plugin plugin) {
        String pluginName = plugin.getName();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, pluginName + "-async-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        this.pool = Executors.newCachedThreadPool(factory);
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (shutdown) {
            future.completeExceptionally(new IllegalStateException("AsyncExecutor shut down"));
            return future;
        }
        pool.execute(() -> {
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
        pool.shutdown();
        try {
            // Let in-flight work (audit fsyncs, JDBC writes) drain before onDisable closes
            // the connection pool.
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
