package ec.gob.simertpi.infrastructure.identity.smtp;

import ec.gob.simertpi.application.identity.smtp.SmtpTransport.Authentication;
import ec.gob.simertpi.application.identity.smtp.SmtpTransport.SmtpSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaMailSmtpTransportTest {
    private final JavaMailSmtpTransport transport = new JavaMailSmtpTransport();

    @Test void oauthUsesOnlyXoauth2AndRequiresVerifiedStartTls() {
        var properties = JavaMailSmtpTransport.smtpProperties(outlook(), Authentication.XOAUTH2);

        assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.auth.mechanisms")).isEqualTo("XOAUTH2");
        assertThat(properties.getProperty("mail.smtp.auth.login.disable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.auth.plain.disable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.auth.xoauth2.disable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
    }

    @Test void oauthRejectsWrongHostPortAndTlsModeBeforeConnecting() {
        assertThatThrownBy(() -> transport.testConnection(
                new SmtpSettings("smtp.gmail.com", 587, "STARTTLS", "user@example.test", "user@example.test", "SIMERTPI"),
                "token".toCharArray(), Authentication.XOAUTH2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.testConnection(
                new SmtpSettings("smtp-mail.outlook.com", 465, "SMTPS", "user@outlook.com", "user@outlook.com", "SIMERTPI"),
                "token".toCharArray(), Authentication.XOAUTH2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JavaMailSmtpTransport.smtpProperties(
                new SmtpSettings("smtp-mail.outlook.com", 587, "SMTPS", "user@outlook.com", "user@outlook.com", "SIMERTPI"),
                Authentication.XOAUTH2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void passwordAuthenticationRemainsExplicitAndDoesNotForceOauthMechanism() {
        var properties = JavaMailSmtpTransport.smtpProperties(
                new SmtpSettings("smtp.gmail.com", 587, "STARTTLS", "user@example.test", "user@example.test", "SIMERTPI"),
                Authentication.PASSWORD);
        assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.auth.mechanisms")).isNull();
        assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    }

    @Test void legacyTransportDefaultsRejectOauthInsteadOfFallingBackToPassword() {
        var unsupported = new ec.gob.simertpi.application.identity.smtp.SmtpTransport() {
            @Override public void testConnection(SmtpSettings settings, char[] password) { }
            @Override public void sendTestEmail(SmtpSettings settings, char[] password, String recipient) { }
        };
        assertThatThrownBy(() -> unsupported.testConnection(outlook(), "token".toCharArray(), Authentication.XOAUTH2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no admite");
    }

    private static SmtpSettings outlook() {
        return new SmtpSettings("smtp-mail.outlook.com", 587, "STARTTLS", "sender@outlook.com", "sender@outlook.com", "SIMERTPI");
    }
}
