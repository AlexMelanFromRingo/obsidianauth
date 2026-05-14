package org.alex_melan.obsidianauth.core.storage;

import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.alex_melan.obsidianauth.core.async.AsyncExecutor;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway migrations on plugin enable, dispatched through {@link AsyncExecutor}.
 *
 * <p>Migration scripts live under {@code core/src/main/resources/db/migration/} and use
 * Flyway placeholder substitution to handle the SQLite-vs-MySQL split — the
 * {@link Dialect} provides the substitution map.
 *
 * <p>Refuses to return success if any migration fails — callers (the plugin's
 * {@code onEnable}) MUST treat a failed future as fatal (FR-008 failure-closed at boot).
 */
public final class MigrationRunner {

    private final AsyncExecutor async;
    private final DataSource dataSource;
    private final Dialect dialect;

    public MigrationRunner(AsyncExecutor async, DataSource dataSource, Dialect dialect) {
        this.async = async;
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    public CompletableFuture<Integer> migrate() {
        return async.submit(this::migrateSync);
    }

    int migrateSync() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(dialect.placeholders())
                .baselineOnMigrate(true)
                .load();
        var result = flyway.migrate();
        return result.migrationsExecuted;
    }
}
