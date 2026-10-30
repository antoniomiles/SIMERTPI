package ec.gob.simertpi.application.identity.invitation;

import ec.gob.simertpi.application.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Delivers committed activation tokens without persisting or returning the secret. */
@Service
public class ActivationInvitationDeliveryService {
    public record Delivery(String status, OffsetDateTime expiresAt) { }
    private record Recipient(UUID id, String email, String displayName, String username, List<String> roles) { }
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final InvitationEmailSender sender;
    private final String publicBaseUrl;

    public ActivationInvitationDeliveryService(JdbcTemplate jdbc, AuditService audit, InvitationEmailSender sender,
            @Value("${simertpi.invitation-email.public-base-url:}") String publicBaseUrl) {
        this.jdbc=jdbc; this.audit=audit; this.sender=sender;
        this.publicBaseUrl=publicBaseUrl==null?"":publicBaseUrl.replaceAll("/+$", "");
    }

    /** Call only after the account/token transaction has committed. */
    @Transactional
    public Delivery deliver(UUID userId,String token,OffsetDateTime expiresAt,String actorUsername) {
        Recipient recipient=recipient(userId);
        InvitationEmailSender.DeliveryResult result;
        String provider=sender.providerCode();
        if(!sender.configured()||publicBaseUrl.isBlank()) {
            result=InvitationEmailSender.DeliveryResult.unconfigured(); provider="UNCONFIGURED";
        } else {
            String url=publicBaseUrl+"/activate-account#token="+URLEncoder.encode(token,StandardCharsets.UTF_8);
            try {
                result=sender.send(recipient.email(),recipient.displayName(),recipient.username(),recipient.roles(),url,expiresAt);
            } catch(RuntimeException failure) {
                // Provider exceptions are intentionally reduced to a non-sensitive status.
                result=InvitationEmailSender.DeliveryResult.failed();
            }
        }
        String status=result.status().name();
        UUID actorId=actorUsername==null?null:jdbc.query("SELECT id FROM identity.users WHERE username=?",rs->rs.next()?rs.getObject(1,UUID.class):null,actorUsername);
        jdbc.update("""
          INSERT INTO identity.activation_delivery_attempts(id,user_id,actor_user_id,status,provider,safe_error_code,expires_at,attempted_at)
          VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
          """,UUID.randomUUID(),userId,actorId,status,provider,
                result.status()==InvitationEmailSender.Status.FAILED?"PROVIDER_REJECTED":null,expiresAt);
        audit.recordOutcome("ADMIN_ACTIVATION_DELIVERY","USER",userId,
                result.status()==InvitationEmailSender.Status.ACCEPTED_BY_PROVIDER?"SUCCESS":"FAILURE",
                java.util.Map.of("status",status,"provider",provider,"expiresAt",expiresAt));
        return new Delivery(status,expiresAt);
    }

    private Recipient recipient(UUID id) {
        return jdbc.query("""
          SELECT u.id,u.email,u.first_name || ' ' || u.last_name AS display_name,u.username,
                 COALESCE(array_agg(DISTINCT r.display_name ORDER BY r.display_name) FILTER(WHERE r.display_name IS NOT NULL),ARRAY[]::varchar[]) AS role_names
            FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id
            JOIN identity.roles r ON r.id=ur.role_id
           WHERE u.id=? GROUP BY u.id
          """,rs->{
            if(!rs.next()) throw new IllegalArgumentException("No se encontró la cuenta para entregar la invitación.");
            java.sql.Array a=rs.getArray("role_names"); Object[] raw=a==null?new Object[0]:(Object[])a.getArray();
            return new Recipient(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),
                    rs.getString("username"),java.util.Arrays.stream(raw).map(String::valueOf).toList());
        },id);
    }
}
