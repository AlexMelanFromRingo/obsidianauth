package org.alex_melan.obsidianauth.velocity.async;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;

/**
 * Velocity async dispatch via {@code proxy.getScheduler().buildTask(plugin, ...).schedule()}.
 *
 * <p>Velocity has no "main thread" — all event handling already runs on the proxy's event-loop
 * pool — but this executor still serves the purpose of moving HMAC verification, channel codec
 * work, and any future I/O off the event loop and onto the proxy's dedicated worker pool.
 */
public final class VelocityAsyncExecutor implements AsyncExecutor {

    private final Object plugin;
    private final ProxyServer proxy;
    private volatile boolean shutdown = false;

    public VelocityAsyncExecutor(Object plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (shutdown) {
            future.completeExceptionally(new IllegalStateException("AsyncExecutor shut down"));
            return future;
        }
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }).schedule();
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
    }
}
