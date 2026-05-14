package org.alex_melan.obsidianauth.core.audit;

/**
 * Thrown by {@link AuditChain#verifyOnStartup()} when the persisted {@code audit_head}
 * pointer does not match the tail of {@code audit.log} — evidence that the tamper-evident
 * chain has been altered out-of-band.
 *
 * <p>Per FR-008 (failure-closed) the plugin MUST treat this as fatal and refuse to enable.
 */
public final class AuditTamperException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditTamperException(String message) {
        super(message);
    }
}
