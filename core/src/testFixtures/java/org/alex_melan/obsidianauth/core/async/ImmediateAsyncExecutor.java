package org.alex_melan.obsidianauth.core.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Test fixture: runs every submitted task synchronously on the calling thread.
 *
 * <p>Use this in unit tests to assert behaviour without spinning up real threads. It is also
 * usable as a {@link SyncExecutor} stand-in for the same reason.
 *
 * <p>Production code MUST NOT use this — see the platform-specific implementations under
 * {@code paper-plugin/async/} and {@code velocity-plugin/async/}.
 */
public final class ImmediateAsyncExecutor implements AsyncExecutor, SyncExecutor {

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public CompletableFuture<Void> submit(Runnable work) {
        try {
            work.run();
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public void shutdown() {
        // no-op
    }

    @Override
    public void postToMainThread(Runnable task) {
        task.run();
    }

    @Override
    public Executor asExecutor() {
        return Runnable::run;
    }
}
