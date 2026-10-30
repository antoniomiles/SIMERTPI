package ec.gob.simertpi.infrastructure.identity.invitation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simertpi.invitation-email")
public record InvitationSmtpSettings(String from, String publicBaseUrl) {
    public InvitationSmtpSettings {
        from = from == null ? "" : from.trim();
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }
}
