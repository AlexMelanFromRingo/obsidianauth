package org.alex_melan.obsidianauth.core.crypto;

/**
 * Thrown when {@link AesGcmSealer#open} fails the GCM authentication step. The cause may be:
 *
 * <ul>
 *   <li>Wrong master key (key rotated, wrong source resolved).</li>
 *   <li>Wrong AAD — caller passed the wrong player UUID or key-version.</li>
 *   <li>Ciphertext / nonce / tag tampering.</li>
 * </ul>
 *
 * <p>The caller MUST treat this as an authentication failure, not a system error. No
 * sensitive material is included in the exception message.
 */
public final class AesGcmAuthenticationException extends Exception {

    private static final long serialVersionUID = 1L;

    public AesGcmAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
