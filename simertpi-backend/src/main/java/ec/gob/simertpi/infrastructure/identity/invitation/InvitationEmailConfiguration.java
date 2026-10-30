package ec.gob.simertpi.infrastructure.identity.invitation;

import ec.gob.simertpi.application.identity.invitation.InvitationEmailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationSmtpSettings.class)
public class InvitationEmailConfiguration {
    InvitationEmailSender invitationEmailSender(InvitationSmtpSettings settings, Environment environment,
                                                 org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSender) {
        return invitationEmailSender(settings, environment, mailSender, null, null, null, null);
    }

    @Bean
    @ConditionalOnMissingBean(InvitationEmailSender.class)
    InvitationEmailSender invitationEmailSender(InvitationSmtpSettings settings, Environment environment,
                                                 org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSender,
                                                 ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService dynamicSmtp,
                                                 ec.gob.simertpi.application.identity.smtp.SmtpHostPolicy hostPolicy,
                                                 ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService microsoftOAuth,
                                                 ec.gob.simertpi.application.identity.smtp.SmtpTransport smtpTransport) {
        String provider = environment.getProperty("simertpi.invitation-email.provider", "UNCONFIGURED");
        String host = environment.getProperty("spring.mail.host", "");
        JavaMailSender sender = mailSender.getIfAvailable();
        if (dynamicSmtp != null || "SMTP".equalsIgnoreCase(provider) && StringUtils.hasText(host)
                && StringUtils.hasText(settings.from()) && validHttpsBaseUrl(settings.publicBaseUrl()) && sender != null) {
            return new SmtpInvitationEmailSender(settings, sender, dynamicSmtp, hostPolicy, microsoftOAuth, smtpTransport);
        }
        return new InvitationEmailSender() {
            @Override public String providerCode() { return "UNCONFIGURED"; }
            @Override public DeliveryResult send(String destination, String displayName, String username,
                                                  java.util.List<String> roleNames, String activationUrl,
                                                  java.time.OffsetDateTime expiresAt) {
                return DeliveryResult.unconfigured();
            }
        };
    }

    private static boolean validHttpsBaseUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
