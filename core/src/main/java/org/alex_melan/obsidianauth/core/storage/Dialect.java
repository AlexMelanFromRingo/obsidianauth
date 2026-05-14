package org.alex_melan.obsidianauth.core.storage;

import java.util.Map;

/**
 * Per-backend SQL flavor selector. Used by {@code MigrationRunner} to populate Flyway
 * placeholders so a single migration script works on SQLite and MySQL.
 */
public enum Dialect {

    SQLITE(Map.ofEntries(
            Map.entry("binary16_type",  "BLOB"),
            Map.entry("binary12_type",  "BLOB"),
            Map.entry("binary32_type",  "BLOB"),
            Map.entry("blob_type",      "BLOB"),
            Map.entry("int_unsigned",   "INTEGER"),
            Map.entry("tinyint",        "INTEGER"))),

    MYSQL(Map.ofEntries(
            Map.entry("binary16_type",  "BINARY(16)"),
            Map.entry("binary12_type",  "BINARY(12)"),
            Map.entry("binary32_type",  "BINARY(32)"),
            Map.entry("blob_type",      "VARBINARY(255)"),
            Map.entry("int_unsigned",   "INT UNSIGNED"),
            Map.entry("tinyint",        "TINYINT UNSIGNED")));

    private final Map<String, String> placeholders;

    Dialect(Map<String, String> placeholders) {
        this.placeholders = Map.copyOf(placeholders);
    }

    /** Flyway placeholder map for this dialect. */
    public Map<String, String> placeholders() {
        return placeholders;
    }

    public static Dialect fromString(String name) {
        return switch (name.toLowerCase()) {
            case "sqlite" -> SQLITE;
            case "mysql"  -> MYSQL;
            default       -> throw new IllegalArgumentException("Unknown storage backend: " + name);
        };
    }
}
