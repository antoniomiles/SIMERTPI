package ec.gob.simertpi.infrastructure.identity.smtp;

import ec.gob.simertpi.application.identity.smtp.SmtpTransport;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

final class JavaMailSmtpTransport implements SmtpTransport {
    @Override public void testConnection(SmtpSettings settings, char[] password) {
        testConnection(settings, password, Authentication.PASSWORD);
    }

    @Override public void testConnection(SmtpSettings settings, char[] credential, Authentication authentication) {
        JavaMailSenderImpl sender = sender(settings, credential, authentication);
        try {
            var transport = sender.getSession().getTransport("smtp");
            try { transport.connect(settings.host(), settings.port(), settings.username(), new String(credential)); }
            finally { if (transport.isConnected()) transport.close(); }
        } catch (MessagingException failure) {
            // Do not expose provider response text: it can contain account or authentication details.
            throw new IllegalStateException("No fue posible establecer una conexiÃ³n SMTP segura");
        }
    }

    @Override public void sendTestEmail(SmtpSettings settings, char[] password, String recipient) {
        sendTestEmail(settings, password, recipient, Authentication.PASSWORD);
    }

    @Override public void sendTestEmail(SmtpSettings settings, char[] credential, String recipient,
                                        Authentication authentication) {
        JavaMailSenderImpl sender = sender(settings, credential, authentication);
        try {
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(new InternetAddress(settings.fromAddress(), settings.displayName(), StandardCharsets.UTF_8.name()));
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(recipient));
            message.setSubject("SIMERTPI | Prueba de configuraciÃ³n de correo", StandardCharsets.UTF_8.name());
            message.setText("Este es un mensaje de prueba de la configuraciÃ³n SMTP de SIMERTPI.", StandardCharsets.UTF_8.name());
            sender.send(message);
        } catch (Exception failure) {
            // Provider exceptions may include AUTH material. Return only a stable, sanitized failure.
            throw new IllegalStateException("El proveedor SMTP no aceptÃ³ el correo de prueba");
        }
    }

    @Override public void sendMessage(SmtpSettings settings, char[] credential, Authentication authentication,
                                      MimeMessage message) {
        JavaMailSenderImpl sender = sender(settings, credential, authentication);
        try {
            sender.send(message);
        } catch (RuntimeException failure) {
            // Provider exceptions can contain the SASL response or account details.
            throw new IllegalStateException("El proveedor SMTP no acepto el mensaje");
        }
    }

    private JavaMailSenderImpl sender(SmtpSettings settings, char[] credential, Authentication authentication) {
        if (credential == null || credential.length == 0) throw new IllegalArgumentException("La credencial SMTP es obligatoria");
        if (authentication == null) throw new IllegalArgumentException("El mÃ©todo de autenticaciÃ³n SMTP no es vÃ¡lido");
        if (authentication == Authentication.XOAUTH2) validateMicrosoftOAuthSettings(settings);

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        // Jakarta Mail currently requires String for Transport.connect. Callers provide a short-lived
        // token and clear their char[] after this operation; neither this value nor exceptions are logged.
        sender.setPassword(new String(credential));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = smtpProperties(settings, authentication);
        sender.setJavaMailProperties(properties);
        return sender;
    }

    static Properties smtpProperties(SmtpSettings settings, Authentication authentication) {
        if (authentication == null) throw new IllegalArgumentException("El mÃ©todo de autenticaciÃ³n SMTP no es vÃ¡lido");
        if (authentication == Authentication.XOAUTH2) validateMicrosoftOAuthSettings(settings);
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        if (authentication == Authentication.XOAUTH2) {
            properties.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            properties.put("mail.smtp.auth.login.disable", "true");
            properties.put("mail.smtp.auth.plain.disable", "true");
            properties.put("mail.smtp.auth.xoauth2.disable", "false");
        }
        if ("STARTTLS".equals(settings.security())) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.enable", "false");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if ("SMTPS".equals(settings.security()) && authentication == Authentication.PASSWORD) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.starttls.enable", "false");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else {
            throw new IllegalArgumentException("El transporte SMTP debe usar TLS compatible con la autenticaciÃ³n");
        }
        return properties;
    }

    private static void validateMicrosoftOAuthSettings(SmtpSettings settings) {
        if (settings == null || !"smtp-mail.outlook.com".equalsIgnoreCase(settings.host())
                || settings.port() != 587 || !"STARTTLS".equals(settings.security())
                || settings.username() == null || settings.username().isBlank()) {
            throw new IllegalArgumentException("Microsoft OAuth2 requiere smtp-mail.outlook.com:587 con STARTTLS");
        }
    }
}
