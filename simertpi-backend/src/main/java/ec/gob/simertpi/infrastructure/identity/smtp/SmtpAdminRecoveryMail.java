package ec.gob.simertpi.infrastructure.identity.smtp;

import ec.gob.simertpi.application.identity.admin.AdminRecoveryMail;
import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService;
import ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService;
import ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy;
import ec.gob.simertpi.application.identity.smtp.SmtpTransport;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
class SmtpAdminRecoveryMail implements AdminRecoveryMail {
    private final SmtpConfigurationService smtp;
    private final MicrosoftOAuthSmtpService microsoft;
    private final SmtpHostPolicy hosts;
    private final SmtpTransport transport;

    SmtpAdminRecoveryMail(SmtpConfigurationService smtp, MicrosoftOAuthSmtpService microsoft,
            SmtpHostPolicy hosts, SmtpTransport transport) {
        this.smtp = smtp;
        this.microsoft = microsoft;
        this.hosts = hosts;
        this.transport = transport;
    }

    @Override
    public void send(String recipient, String subject, String body) {
        if (microsoft.isActive()) {
            var credential = microsoft.activeCredential();
            if (credential == null) throw new IllegalStateException("Microsoft SMTP credential unavailable");
            send(credential.settings(), credential.accessToken(), SmtpTransport.Authentication.XOAUTH2,
                    recipient, subject, body);
        } else {
            var credential = smtp.activeCredential();
            if (credential == null) throw new IllegalStateException("Administrative SMTP is not configured");
            send(credential.settings(), credential.password(), SmtpTransport.Authentication.PASSWORD,
                    recipient, subject, body);
        }
    }

    private void send(SmtpTransport.SmtpSettings settings, char[] secret, SmtpTransport.Authentication mode,
            String recipient, String subject, String body) {
        try {
            if (mode == SmtpTransport.Authentication.XOAUTH2) {
                hosts.validateMicrosoftOAuthHost(settings.host());
            } else {
                hosts.validate(settings.host());
            }
            var message = new JavaMailSenderImpl().createMimeMessage();
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(settings.fromAddress(), settings.displayName(), StandardCharsets.UTF_8.name()));
            helper.setTo(new InternetAddress(recipient, true));
            helper.setSubject(subject);
            helper.setText(body);
            transport.sendMessage(settings, secret, mode, message);
        } catch (MessagingException | UnsupportedEncodingException invalid) {
            throw new IllegalStateException("Administrative recovery message could not be prepared", invalid);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
