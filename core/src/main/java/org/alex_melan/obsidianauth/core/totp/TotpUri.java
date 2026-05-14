package org.alex_melan.obsidianauth.core.totp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Builds the {@code otpauth://totp/...} provisioning URI consumed by authenticator apps.
 *
 * <p>Per the spec, the issuer label MUST NOT contain ASCII colon ({@code :}) — it would
 * collide with the URI path's {@code issuer:account} separator. This builder rejects
 * illegal characters at build time rather than emitting an invalid URI.
 */
public final class TotpUri {

    private static final Pattern ILLEGAL_ISSUER = Pattern.compile("[:?#&]");

    private TotpUri() {
        // static-only
    }

    /**
     * @param issuer       brand-name label (e.g. {@code "ExampleNet"}) — must not contain {@code :}
     * @param account      account-portion label (typically the player username)
     * @param base32Secret the shared secret in base32 (no padding)
     * @param digits       6 or 8
     * @param period       RFC 6238 step in seconds (30)
     * @param algorithm    HMAC algorithm
     */
    public static String build(String issuer, String account, String base32Secret,
                               int digits, int period, TotpGenerator.Algorithm algorithm) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must be non-empty");
        }
        if (ILLEGAL_ISSUER.matcher(issuer).find()) {
            throw new IllegalArgumentException(
                    "issuer must not contain ':', '?', '#', or '&' (got '" + issuer + "')");
        }
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("account must be non-empty");
        }
        if (digits != 6 && digits != 8) {
            throw new IllegalArgumentException("digits must be 6 or 8");
        }

        String encIssuer  = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encAccount = URLEncoder.encode(account, StandardCharsets.UTF_8);

        // Standard form: otpauth://totp/{issuer}:{account}?secret=...&issuer=...&digits=...&period=...&algorithm=...
        return "otpauth://totp/" + encIssuer + ":" + encAccount
                + "?secret="    + base32Secret
                + "&issuer="    + encIssuer
                + "&digits="    + digits
                + "&period="    + period
                + "&algorithm=" + algorithm.name();
    }
}
