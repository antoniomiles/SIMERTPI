package ec.gob.simertpi.testsupport;

import ec.gob.simertpi.application.identity.smtp.SmtpTransport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;

/** Mail transport replacement used only by the disposable authenticated browser harness. */
@TestConfiguration(proxyBeanMethods = false)
public class SmtpBrowserTestConfiguration {
    private static final String EXPECTED_HOST = "smtp.gmail.com";
    private static final String EXPECTED_RECIPIENT = "smtp-test@example.invalid";

    @Bean(name = "smtpBrowserTestTransport")
    @Primary
    SmtpTransport smtpTransport() {
        return new SmtpTransport() {
            @Override
            public void testConnection(SmtpSettings settings, char[] password) {
                verify(settings, password);
            }

            @Override
            public void sendTestEmail(SmtpSettings settings, char[] password, String recipient) {
                verify(settings, password);
                if (!EXPECTED_RECIPIENT.equals(recipient)) {
                    throw new IllegalArgumentException("The browser harness permits only its synthetic recipient");
                }
            }

            private void verify(SmtpSettings settings, char[] password) {
                if (!EXPECTED_HOST.equals(settings.host()) || settings.port() != 587
                        || !"STARTTLS".equals(settings.security())
                        || password == null || password.length == 0) {
                    throw new IllegalArgumentException("Unexpected synthetic SMTP settings");
                }
                // The password exists only in the test request and the method argument.
                // No external SMTP socket is opened by this adapter.
                Arrays.fill(password, '\0');
            }
        };
    }
}
