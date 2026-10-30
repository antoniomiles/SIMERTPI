package ec.gob.simertpi.application.identity.smtp;

public interface SmtpTransport {
    void testConnection(SmtpSettings settings, char[] password);
    void sendTestEmail(SmtpSettings settings, char[] password, String recipient);

    record SmtpSettings(String host, int port, String security, String username, String fromAddress,
                        String displayName) { }
}
