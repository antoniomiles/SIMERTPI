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
        JavaMailSenderImpl sender = sender(settings, password);
        try {
            var transport = sender.getSession().getTransport("smtp");
            try { transport.connect(settings.host(), settings.port(), settings.username(), new String(password)); }
            finally { if (transport.isConnected()) transport.close(); }
        } catch (MessagingException failure) {
            throw new IllegalStateException("No fue posible establecer una conexión SMTP segura", failure);
        }
    }

    @Override public void sendTestEmail(SmtpSettings settings, char[] password, String recipient) {
        JavaMailSenderImpl sender = sender(settings, password);
        try {
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(new InternetAddress(settings.fromAddress(), settings.displayName(), StandardCharsets.UTF_8.name()));
            message.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(recipient));
            message.setSubject("SIMERTPI | Prueba de configuración de correo", StandardCharsets.UTF_8.name());
            message.setText("Este es un mensaje de prueba de la configuración SMTP de SIMERTPI.", StandardCharsets.UTF_8.name());
            sender.send(message);
        } catch (Exception failure) {
            throw new IllegalStateException("El proveedor SMTP no aceptó el correo de prueba", failure);
        }
    }

    private JavaMailSenderImpl sender(SmtpSettings settings, char[] password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(new String(password));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        if ("STARTTLS".equals(settings.security())) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.enable", "false");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if ("SMTPS".equals(settings.security())) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.starttls.enable", "false");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else {
            throw new IllegalArgumentException("El transporte SMTP debe usar TLS");
        }
        return sender;
    }
}
