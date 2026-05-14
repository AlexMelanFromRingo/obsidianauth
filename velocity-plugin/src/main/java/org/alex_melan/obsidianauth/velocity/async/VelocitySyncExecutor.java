package org.alex_melan.obsidianauth.velocity.async;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.Executor;
import org.alex_melan.obsidianauth.core.async.SyncExecutor;

/**
 * Velocity sync dispatch.
 *
 * <p>Velocity has no main thread; this implementation delegates to the same scheduler as
 * {@link VelocityAsyncExecutor}. Provided so service code can use the {@link SyncExecutor}
 * abstraction uniformly across platforms without conditionals.
 */
public final class VelocitySyncExecutor implements SyncExecutor {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Executor executor;

    public VelocitySyncExecutor(Object plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.executor = task -> proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public void postToMainThread(Runnable task) {
        proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public Executor asExecutor() {
        return executor;
    }
}
