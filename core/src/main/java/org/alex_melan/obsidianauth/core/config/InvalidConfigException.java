package org.alex_melan.obsidianauth.core.config;

/**
 * Thrown by {@link ConfigValidator} when a configuration value is outside its supported
 * range. The plugin MUST refuse to enable when this is thrown — FR-025 explicitly forbids
 * silent fallbacks for invalid configuration.
 */
public final class InvalidConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String fieldPath;

    public InvalidConfigException(String fieldPath, String message) {
        super(fieldPath + ": " + message);
        this.fieldPath = fieldPath;
    }

    public String fieldPath() {
        return fieldPath;
    }
}
