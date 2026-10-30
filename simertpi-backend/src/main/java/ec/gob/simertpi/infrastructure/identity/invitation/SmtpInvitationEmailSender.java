package ec.gob.simertpi.infrastructure.identity.invitation;

import ec.gob.simertpi.application.identity.invitation.InvitationEmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** SMTP implementation. Provider acceptance does not imply mailbox delivery. */
final class SmtpInvitationEmailSender implements InvitationEmailSender {
    private static final Logger log = LoggerFactory.getLogger(SmtpInvitationEmailSender.class);
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm '(hora de Ecuador)'", Locale.forLanguageTag("es-EC"));
    private final InvitationSmtpSettings settings;
    private final JavaMailSender mailSender;
    private final ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService dynamicSmtp;
    private final ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy hostPolicy;
    private final ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService microsoftOAuth;
    private final ec.gob.simertpi.application.identity.smtp.SmtpTransport smtpTransport;

    SmtpInvitationEmailSender(InvitationSmtpSettings settings, JavaMailSender mailSender) {
        this(settings, mailSender, null, null, null, null);
    }

    SmtpInvitationEmailSender(InvitationSmtpSettings settings, JavaMailSender mailSender,
                              ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService dynamicSmtp) {
        this(settings, mailSender, dynamicSmtp, null, null, null);
    }

    SmtpInvitationEmailSender(InvitationSmtpSettings settings, JavaMailSender mailSender,
                              ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService dynamicSmtp,
                              ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy hostPolicy) {
        this(settings, mailSender, dynamicSmtp, hostPolicy, null, null);
    }

    SmtpInvitationEmailSender(InvitationSmtpSettings settings, JavaMailSender mailSender,
                              ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService dynamicSmtp,
                              ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy hostPolicy,
                              ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService microsoftOAuth,
                              ec.gob.simertpi.application.identity.smtp.SmtpTransport smtpTransport) {
        this.settings = settings;
        this.mailSender = mailSender;
        this.dynamicSmtp = dynamicSmtp;
        this.hostPolicy = hostPolicy;
        this.microsoftOAuth = microsoftOAuth;
        this.smtpTransport = smtpTransport;
    }

    @Override
    public String providerCode() {
        return "SMTP";
    }

    @Override
    public boolean configured() {
        return dynamicSmtp == null || dynamicSmtp.configured() || microsoftOAuth != null && microsoftOAuth.isActive();
    }

    @Override
    public DeliveryResult send(String destination, String displayName, String username, List<String> roleNames,
                               String activationUrl, OffsetDateTime expiresAt) {
        ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.ActiveCredential active = null;
        ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService.DeliveryCredential oauthActive = null;
        try {
            validateConfiguration();
            validateActivationUrl(activationUrl);
            if (microsoftOAuth != null && microsoftOAuth.isActive()) oauthActive = microsoftOAuth.activeCredential();
            active = dynamicSmtp == null || oauthActive != null ? null : dynamicSmtp.activeCredential();
            if (dynamicSmtp != null && active == null && oauthActive == null) return DeliveryResult.unconfigured();
            if (active != null) {
                if (hostPolicy == null) throw new IllegalStateException("SMTP egress policy is unavailable");
                // Re-resolve and enforce the external hostname allowlist immediately before opening SMTP.
                // JavaMail resolves the hostname again; without socket pinning DNS rebinding remains a residual risk.
                hostPolicy.validate(active.settings().host());
            }
            if (oauthActive != null) hostPolicy.validateMicrosoftOAuthHost(oauthActive.settings().host());
            JavaMailSender selected = oauthActive != null ? null : dynamicSmtp == null ? mailSender : dynamicSender(active);
            if (oauthActive == null && selected == null) return DeliveryResult.unconfigured();
            MimeMessage message = oauthActive != null ? new JavaMailSenderImpl().createMimeMessage() : selected.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            if (oauthActive != null) helper.setFrom(new jakarta.mail.internet.InternetAddress(oauthActive.settings().fromAddress(), oauthActive.settings().displayName(), StandardCharsets.UTF_8.name()));
            else if (active == null) helper.setFrom(settings.from());
            else helper.setFrom(new jakarta.mail.internet.InternetAddress(active.settings().fromAddress(), active.settings().displayName(), StandardCharsets.UTF_8.name()));
            helper.setTo(clean(destination));
            helper.setSubject("SIMERTPI | Activación de tu cuenta");
            String roleText = safeText(String.join(", ", roleNames));
            String expiry = EXPIRY_FORMAT.format(expiresAt.atZoneSameInstant(ZoneId.of("America/Guayaquil")));
            helper.setText(plain(displayName, username, roleText, activationUrl, expiry),
                    html(displayName, username, roleText, activationUrl, expiry));
            if (oauthActive != null) {
                if (smtpTransport == null) throw new IllegalStateException("Microsoft SMTP transport is unavailable");
                smtpTransport.sendMessage(oauthActive.settings(), oauthActive.accessToken(),
                        ec.gob.simertpi.application.identity.smtp.SmtpTransport.Authentication.XOAUTH2, message);
            } else selected.send(message);
            // No recipient, activation URL, token, or message body is logged.
            return DeliveryResult.acceptedByProvider();
        } catch (RuntimeException | MessagingException | IOException failure) {
            log.warn("Activation invitation was not accepted by the configured mail provider ({}).",
                    failure.getClass().getSimpleName());
            return DeliveryResult.failed();
        } finally {
            if (active != null) java.util.Arrays.fill(active.password(), '\0');
            if (oauthActive != null) java.util.Arrays.fill(oauthActive.accessToken(), '\0');
        }
    }

    private JavaMailSender dynamicSender(ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.ActiveCredential active) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(active.settings().host());
        sender.setPort(active.settings().port());
        sender.setUsername(active.settings().username());
        sender.setPassword(new String(active.password()));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        if ("STARTTLS".equals(active.settings().security())) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        return sender;
    }

    private void validateConfiguration() {
        if ((dynamicSmtp == null || !dynamicSmtp.configured()) && (microsoftOAuth == null || !microsoftOAuth.isActive()) && (settings.from().isBlank() || mailSender == null))
            throw new IllegalStateException("Invitation sender address is not configured");
    }

    private void validateActivationUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Activation URL is invalid");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawQuery() != null || uri.getRawFragment() == null
                || !uri.getRawFragment().matches("token=[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Activation URL must carry its secret only in a token fragment");
        }
        URI base = URI.create(settings.publicBaseUrl());
        if (!base.getHost().equalsIgnoreCase(uri.getHost()) || !base.getScheme().equalsIgnoreCase(uri.getScheme())
                || effectivePort(base) != effectivePort(uri)) {
            throw new IllegalArgumentException("Activation URL does not match this environment's configured public origin");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String html(String displayName, String username, String role, String activationUrl, String expiry)
            throws IOException {
        String template = resource("templates/mail/admin-activation.html");
        return template.replace("{{name}}", escape(displayName))
                .replace("{{username}}", escape(username))
                .replace("{{role}}", escape(role))
                .replace("{{activationUrl}}", escape(activationUrl))
                .replace("{{expiry}}", escape(expiry));
    }

    private static String plain(String displayName, String username, String role, String activationUrl, String expiry)
            throws IOException {
        String template = resource("templates/mail/admin-activation.txt");
        return template.replace("{{name}}", safeText(displayName))
                .replace("{{username}}", safeText(username))
                .replace("{{role}}", role)
                .replace("{{activationUrl}}", activationUrl)
                .replace("{{expiry}}", expiry);
    }

    private static String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(safeText(value), StandardCharsets.UTF_8.name());
    }

    private static String safeText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
    }

    private static String clean(String value) {
        String result = safeText(value);
        if (result.isBlank() || result.length() > 255 || result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Recipient address is invalid");
        }
        return result;
    }
}
