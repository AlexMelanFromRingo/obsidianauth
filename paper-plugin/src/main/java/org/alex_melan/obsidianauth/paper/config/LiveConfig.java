package org.alex_melan.obsidianauth.paper.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.alex_melan.obsidianauth.core.config.TotpConfig;

/**
 * Mutable holder for the currently-active {@link TotpConfig}.
 *
 * <p>The plugin wires this single instance into every config-consuming service
 * ({@code EnrollmentOrchestrator}, {@code ChatVerificationService},
 * {@code PreAuthChatListener}). {@code /2fa-admin reload} swaps the reference atomically;
 * services snapshot {@link #get()} once at the start of each operation so a reload that
 * lands mid-operation never produces a torn read.
 *
 * <p>Only the safely-reloadable TOTP knobs live here — storage and encryption settings are
 * NOT hot-reloadable and are captured separately by {@link PaperConfigReloader}.
 */
public final class LiveConfig {

    private final AtomicReference<TotpConfig> ref;

    public LiveConfig(TotpConfig initial) {
        this.ref = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /** The currently-active configuration snapshot. */
    public TotpConfig current() {
        return ref.get();
    }

    /** Atomically replace the active configuration (called by {@code /2fa-admin reload}). */
    public void set(TotpConfig updated) {
        ref.set(Objects.requireNonNull(updated, "updated"));
    }
}
