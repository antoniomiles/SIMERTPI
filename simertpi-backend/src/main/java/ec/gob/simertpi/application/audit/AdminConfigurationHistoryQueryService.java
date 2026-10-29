package ec.gob.simertpi.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminConfigurationHistoryQueryService {
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "zoneId", "dayOfWeek", "startTime", "endTime", "validFrom", "validTo", "active",
            "holidayDate", "name", "holidayType", "tariffed", "normativeReference",
            "email", "firstName", "lastName", "phone", "enabled");
    private static final Set<String> OTHER_FIELDS = Set.of(
            "reason", "changedFields", "previousScheduleId", "previousVersionEnd",
            "effectiveFrom", "previousEnabled", "email", "previousEmail", "firstName",
            "previousFirstName", "lastName", "previousLastName", "phone", "previousPhone", "enabled",
            "roles", "before.roles", "after.roles", "activationExpiresAt", "approvalId", "role");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AdminConfigurationHistoryQueryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Change> search(int limit, int offset) {
        return jdbc.query("""
                SELECT id, occurred_at, actor_id, actor_name, actor_role, action, resource_type,
                       resource_id, result, correlation_id, metadata::text AS metadata
                  FROM audit.functional_audit_log
                 WHERE action IN (
                     'OPERATIONAL_SCHEDULE_CREATED', 'OPERATIONAL_SCHEDULE_UPDATED',
                     'OPERATIONAL_SCHEDULE_REVISED', 'OPERATIONAL_SCHEDULE_END_SCHEDULED',
                     'OPERATIONAL_SCHEDULE_DEACTIVATED', 'CALENDAR_EXCEPTION_CREATED',
                     'CALENDAR_EXCEPTION_UPDATED', 'CALENDAR_EXCEPTION_DEACTIVATED',
                     'ADMIN_USER_UPDATED', 'ADMIN_USER_ENABLED', 'ADMIN_USER_DISABLED',
                     'ADMIN_USER_INVITED', 'ADMIN_USER_ROLES_UPDATED', 'ADMIN_USER_ACTIVATION_REISSUED',
                     'INVITED_ACCOUNT_ACTIVATED', 'ADMIN_BOOTSTRAP',
                     'INSPECTOR_CREATED', 'INSPECTOR_ACTIVATION_REISSUED', 'INSPECTOR_ACTIVATED')
                 ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new Change(
                rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("actor_role"),
                rs.getString("action"), rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("result"), rs.getString("correlation_id"), safeDetails(rs.getString("metadata"))),
                limit, offset);
    }

    private Map<String, String> safeDetails(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = mapper.readTree(raw);
            if (!node.isObject()) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                String key = field.getKey();
                String snapshotName = key.startsWith("before.") || key.startsWith("after.")
                        ? key.substring(key.indexOf('.') + 1) : null;
                boolean allowed = OTHER_FIELDS.contains(key)
                        || (snapshotName != null && SNAPSHOT_FIELDS.contains(snapshotName));
                JsonNode value = field.getValue();
                if (allowed && (value.isTextual() || value.isNumber() || value.isBoolean() || value.isNull())) {
                    String text = value.isNull() ? "" : value.asText();
                    if (text.length() > 256) text = text.substring(0, 256);
                    result.put(key, text);
                }
            });
            return Map.copyOf(result);
        } catch (JsonProcessingException invalidMetadata) {
            return Map.of();
        }
    }

    public record Change(UUID id, OffsetDateTime occurredAt, UUID actorId, String actorName,
                         String actorRole, String action, String resourceType, UUID resourceId,
                         String result, String correlationId, Map<String, String> details) { }
}
