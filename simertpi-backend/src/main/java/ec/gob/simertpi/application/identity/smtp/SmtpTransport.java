package ec.gob.simertpi.application.identity.smtp;

import jakarta.mail.internet.MimeMessage;

public interface SmtpTransport {
    void testConnection(SmtpSettings settings, char[] password);
    void sendTestEmail(SmtpSettings settings, char[] password, String recipient);

    default void sendMessage(SmtpSettings settings, char[] credential, Authentication authentication,
                             MimeMessage message) {
        if (authentication != Authentication.PASSWORD && authentication != Authentication.XOAUTH2) {
            throw new IllegalStateException("El transporte no admite la autenticación solicitada");
        }
        throw new UnsupportedOperationException("El transporte no admite envíos de mensajes");
    }

    /** Explicit authentication mode. XOAUTH2 credentials are short-lived access tokens, never passwords. */
    enum Authentication { PASSWORD, XOAUTH2 }

    default void testConnection(SmtpSettings settings, char[] credential, Authentication authentication) {
        if (authentication != Authentication.PASSWORD) {
            throw new IllegalStateException("El transporte no admite la autenticación solicitada");
        }
        testConnection(settings, credential);
    }

    default void sendTestEmail(SmtpSettings settings, char[] credential, String recipient,
                               Authentication authentication) {
        if (authentication != Authentication.PASSWORD) {
            throw new IllegalStateException("El transporte no admite la autenticación solicitada");
        }
        sendTestEmail(settings, credential, recipient);
    }

    record SmtpSettings(String host, int port, String security, String username, String fromAddress,
                        String displayName) { }
}
