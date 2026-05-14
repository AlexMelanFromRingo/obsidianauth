package org.alex_melan.obsidianauth.core.async;

import java.util.concurrent.Executor;

/**
 * Main / region thread dispatch interface.
 *
 * <p>Use this to post a {@link CompletableFuture} continuation back to the main thread when
 * the continuation touches platform API state (player inventory, session map, event response).
 *
 * <p>Platform-specific backings:
 *
 * <ul>
 *   <li>Regular Paper: {@code Bukkit.getScheduler().runTask}</li>
 *   <li>Folia: {@code GlobalRegionScheduler.run} for global state,
 *       {@code RegionScheduler.run(plugin, location, task)} when a location is in scope</li>
 *   <li>Velocity: no main thread — implementation may delegate to the proxy's scheduler</li>
 * </ul>
 */
public interface SyncExecutor {

    /** Post {@code task} to the main / region thread. Returns immediately. */
    void postToMainThread(Runnable task);

    /**
     * Adapter so callers can write
     * {@code future.thenAcceptAsync(result -> ..., syncExecutor.asExecutor())}.
     */
    Executor asExecutor();
}
