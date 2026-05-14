package org.alex_melan.obsidianauth.core.async;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Off-main / off-region dispatch interface.
 *
 * <p>Every operation that exceeds the ~1 ms latency budget of the main / region thread
 * (JDBC, AES-GCM, HMAC, file I/O, fsync, ZXing encoding) MUST be submitted through this
 * interface. The actual thread pool is platform-specific:
 *
 * <ul>
 *   <li>Regular Paper: {@code Bukkit.getScheduler().runTaskAsynchronously}</li>
 *   <li>Folia: {@code plugin.getServer().getAsyncScheduler().runNow}</li>
 *   <li>Velocity: {@code proxy.getScheduler().buildTask(plugin, ...).schedule()}</li>
 * </ul>
 *
 * <p>Callers MUST NOT invoke {@link CompletableFuture#join()}, {@link CompletableFuture#get()},
 * or {@link CompletableFuture#getNow(Object)} on returned futures from the main / region
 * thread. Use {@code thenAcceptAsync(continuation, syncExecutor.asExecutor())} to post the
 * continuation back to the main thread.
 */
public interface AsyncExecutor {

    <T> CompletableFuture<T> submit(Supplier<T> work);

    CompletableFuture<Void> submit(Runnable work);

    /** Drains the executor. Called on plugin disable. Safe to call multiple times. */
    void shutdown();
}
