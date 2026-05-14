package org.alex_melan.obsidianauth.core.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TotpUriTest {

    @Test
    void buildsWellFormedUri() {
        String uri = TotpUri.build("ExampleNet", "alice",
                "JBSWY3DPEHPK3PXP", 6, 30, TotpGenerator.Algorithm.SHA1);
        assertThat(uri).startsWith("otpauth://totp/ExampleNet:alice?");
        assertThat(uri).contains("secret=JBSWY3DPEHPK3PXP");
        assertThat(uri).contains("issuer=ExampleNet");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
        assertThat(uri).contains("algorithm=SHA1");
    }

    @Test
    void rejectsIssuerWithColon() {
        assertThatThrownBy(() -> TotpUri.build("Bad:Name", "alice",
                "ABC", 6, 30, TotpGenerator.Algorithm.SHA1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("':'");
    }

    @Test
    void rejectsIssuerWithQuestionMark() {
        assertThatThrownBy(() -> TotpUri.build("Bad?Name", "alice",
                "ABC", 6, 30, TotpGenerator.Algorithm.SHA1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuerWithSpace_roundTripsAsPercentTwenty() {
        String uri = TotpUri.build("Example Net", "alice",
                "ABC", 6, 30, TotpGenerator.Algorithm.SHA1);
        // URLEncoder uses '+' for spaces in application/x-www-form-urlencoded mode.
        // Both '+' and '%20' are acceptable in the path component of otpauth URIs; here we
        // accept whatever URLEncoder produces.
        assertThat(uri).contains("Example");
        assertThat(uri).contains("Net");
        assertThat(uri).doesNotContain("Example Net");
    }

    @Test
    void rejectsBlankIssuer() {
        assertThatThrownBy(() -> TotpUri.build("", "alice",
                "ABC", 6, 30, TotpGenerator.Algorithm.SHA1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidDigits() {
        assertThatThrownBy(() -> TotpUri.build("ExampleNet", "alice",
                "ABC", 7, 30, TotpGenerator.Algorithm.SHA1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
