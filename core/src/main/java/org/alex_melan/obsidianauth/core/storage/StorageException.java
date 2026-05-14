package org.alex_melan.obsidianauth.core.storage;

/**
 * Wraps any {@link java.sql.SQLException} raised by the DAOs. Unchecked so callers can
 * compose with {@code CompletableFuture}; surface failures via {@code .exceptionally(...)}.
 */
public final class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
