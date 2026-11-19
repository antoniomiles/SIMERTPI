package ec.gob.simertpi.infrastructure.identity.smtp;

import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService;
import ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService;
import ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy;
import ec.gob.simertpi.application.identity.smtp.SmtpTransport;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpAdminRecoveryMailTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsInstitutionalHtmlUsingExistingTransport(boolean passwordReset) {
        String resetUrl = "https://portal.example.invalid/restablecer-contrasena#token=" + "A".repeat(43);
        var smtp = mock(SmtpConfigurationService.class);
        var microsoft = mock(MicrosoftOAuthSmtpService.class);
        var hosts = mock(SmtpHostPolicy.class);
        var transport = mock(SmtpTransport.class);
        var settings = new SmtpTransport.SmtpSettings("smtp.gmail.com", 587, "STARTTLS",
                "sender@example.invalid", "sender@example.invalid", "SIMERTPI");
        char[] secret = "fixture-secret".toCharArray();
        when(smtp.activeCredential()).thenReturn(new SmtpConfigurationService.ActiveCredential(settings, secret, 1L));

        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(3);
            assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("recipient@example.invalid");
            message.saveChanges();
            assertThat(message.getContentType()).startsWith("text/html").containsIgnoringCase("UTF-8");
            assertThat(message.getFrom()).extracting(Object::toString).singleElement()
                    .asString().contains("sender@example.invalid");
            String html = (String) message.getContent();
            assertThat(html).contains("max-width:600px;width:100%", "background:#176b3a",
                    "Arial,Helvetica,sans-serif", "Administración del Sistema SIMERTPI")
                    .doesNotContain("{{", "ACTIVAR MI CUENTA", "Rol asignado");
            if (passwordReset) {
                assertThat(message.getSubject()).isEqualTo("SIMERTPI | Restablecimiento de contraseña");
                assertThat(html).contains("href=\"" + resetUrl + "\"", ">Restablecer contraseña</a>",
                        "30 minutos", "solo puede utilizarse una vez", "Tu contraseña actual permanecerá sin cambios");
            } else {
                assertThat(message.getSubject()).isEqualTo("SIMERTPI | Recuperación de usuario");
                assertThat(html).contains("<strong>admin.&lt;dev&gt;</strong>", "Tu usuario es:",
                        "Puedes utilizar este usuario para iniciar sesión normalmente").doesNotContain("<dev>", "<a ");
            }
            assertThat((char[]) invocation.getArgument(1)).containsExactly("fixture-secret".toCharArray());
            return null;
        }).when(transport).sendMessage(eq(settings), same(secret), eq(SmtpTransport.Authentication.PASSWORD), any());

        var mail = new SmtpAdminRecoveryMail(smtp, microsoft, hosts, transport);
        if (passwordReset) {
            mail.sendPasswordReset("recipient@example.invalid", resetUrl);
        } else {
            mail.sendUsername("recipient@example.invalid", "admin.<dev>");
        }

        verify(hosts).validate(settings.host());
        verify(transport).sendMessage(eq(settings), same(secret), eq(SmtpTransport.Authentication.PASSWORD), any());
        assertThat(secret).containsOnly('\0');
    }
}
