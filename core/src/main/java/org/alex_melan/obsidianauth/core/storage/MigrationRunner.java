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
        // Configure Flyway with the classloader that loaded THIS class — the plugin's
        // PluginClassLoader at runtime (the core classes are shaded into the plugin JAR),
        // the test classloader under Gradle. Either way it is the classloader that actually
        // has db/migration/*.sql on it; relying on the thread context classloader instead
        // is fragile (a plain worker thread inherits the server's classloader and Flyway
        // then scans the wrong classpath, finding zero migrations).
        Flyway flyway = Flyway.configure(MigrationRunner.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(dialect.placeholders())
                .baselineOnMigrate(true)
                .load();
        var result = flyway.migrate();
        return result.migrationsExecuted;
    }
}
