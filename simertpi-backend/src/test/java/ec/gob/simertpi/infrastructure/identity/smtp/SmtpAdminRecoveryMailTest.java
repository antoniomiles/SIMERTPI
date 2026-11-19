package ec.gob.simertpi.infrastructure.identity.smtp;

import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService;
import ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService;
import ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy;
import ec.gob.simertpi.application.identity.smtp.SmtpTransport;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpAdminRecoveryMailTest {
    @Test
    void usesActiveAdministrativeSmtpAndClearsItsDecryptedCredential() {
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
            assertThat(message.getSubject()).isEqualTo("Recuperación");
            assertThat(message.getContent()).isEqualTo("Mensaje de prueba.");
            assertThat((char[]) invocation.getArgument(1)).containsExactly("fixture-secret".toCharArray());
            return null;
        }).when(transport).sendMessage(eq(settings), same(secret), eq(SmtpTransport.Authentication.PASSWORD), any());

        new SmtpAdminRecoveryMail(smtp, microsoft, hosts, transport)
                .send("recipient@example.invalid", "Recuperación", "Mensaje de prueba.");

        verify(hosts).validate(settings.host());
        verify(transport).sendMessage(eq(settings), same(secret), eq(SmtpTransport.Authentication.PASSWORD), any());
        assertThat(secret).containsOnly('\0');
    }
}
