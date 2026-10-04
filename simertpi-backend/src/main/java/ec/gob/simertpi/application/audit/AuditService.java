package ec.gob.simertpi.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private static final String INSERT = """
            INSERT INTO audit.functional_audit_log
              (id, actor_id, actor_name, actor_role, action, resource_type, resource_id, result,
               correlation_id, source_ip, user_agent, metadata, idempotency_fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT DO NOTHING
            """;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ec.gob.simertpi.application.operations.OperationalMetrics metrics;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void success(String action, String resourceType, UUID resourceId,
                        String idempotencyKey, Map<String, ?> metadata) {
        write(action, resourceType, resourceId, "SUCCESS", idempotencyKey, metadata);
    }

    @Transactional
    public void recordOutcome(String action, String resourceType, UUID resourceId,
                              String result, Map<String, ?> metadata) {
        if (!"SUCCESS".equals(result) && !"FAILURE".equals(result)) {
            throw new IllegalArgumentException("Unsupported audit result");
        }
        write(action, resourceType, resourceId, result, null, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(String action, String resourceType, UUID resourceId,
                        Throwable failure, String idempotencyKey) {
        write(action, resourceType, resourceId, "FAILURE", idempotencyKey,
                Map.of("failureType", failure.getClass().getSimpleName()));
    }

    private void write(String action, String resourceType, UUID resourceId, String result,
                       String idempotencyKey, Map<String, ?> rawMetadata) {
        Actor actor = actor();
        HttpServletRequest request = currentRequest();
        String json;
        try {
            json = mapper.writeValueAsString(safeMetadata(rawMetadata));
        } catch (JsonProcessingException impossible) {
            json = "{}";
        }
        int inserted = jdbc.update(INSERT, UUID.randomUUID(), actor.id(), actor.name(), actor.role(), action, resourceType,
                resourceId, result, MDC.get("correlationId"), request == null ? null : request.getRemoteAddr(),
                request == null ? null : truncate(request.getHeader("User-Agent"), 512), json,
                idempotencyKey == null ? null : sha256(actor.name() + ":" + action + ":" + idempotencyKey));
        if(inserted==1 && metrics!=null)metrics.auditEvent(action,result,rawMetadata);
    }

    private Actor actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return currentRequest() == null ? new Actor(null, "SYSTEM", "SYSTEM")
                    : new Actor(null, "ANONYMOUS", "PUBLIC");
        }
        String role = auth.getAuthorities().stream().map(a -> a.getAuthority()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        if (role.isBlank()) role = "AUTHENTICATED";
        UUID userId = jdbc.query("SELECT id FROM identity.users WHERE username = ?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, auth.getName());
        return new Actor(userId, truncate(auth.getName(), 150), role);
    }

    private Map<String, Object> safeMetadata(Map<String, ?> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (source == null) return safe;
        source.forEach((key, value) -> {
            if (key == null || key.matches("(?i).*(password|secret|token|credential|card|cvv|pan).*")) return;
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof UUID || value instanceof java.time.temporal.TemporalAccessor) {
                safe.put(truncate(key, 80), value == null ? null : truncate(String.valueOf(value), 256));
            }
        });
        return safe;
    }

    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        return attributes.getRequest();
    }

    private String sha256(String input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    private record Actor(UUID id, String name, String role) { }
}
