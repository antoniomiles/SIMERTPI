package ec.gob.simertpi.domain.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@Repository
public class AuditSearchRepository {
    private final JdbcTemplate jdbc;

    public AuditSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditRow> search(UUID actorId, String action, String resourceType, UUID resourceId,
                                 OffsetDateTime from, OffsetDateTime to, String result,
                                 String correlationId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, actor_id, actor_name, actor_role, action, resource_type,
                       resource_id, result, correlation_id, source_ip, user_agent, metadata::text
                FROM audit.functional_audit_log WHERE 1=1
                """);
        List<Object> values = new ArrayList<>();
        add(sql, values, "actor_id", actorId);
        add(sql, values, "action", action);
        add(sql, values, "resource_type", resourceType);
        add(sql, values, "resource_id", resourceId);
        if (from != null) { sql.append(" AND occurred_at >= ?"); values.add(from); }
        if (to != null) { sql.append(" AND occurred_at <= ?"); values.add(to); }
        add(sql, values, "result", result);
        add(sql, values, "correlation_id", correlationId);
        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        values.add(limit);
        values.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> new AuditRow(rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"), rs.getString("actor_role"), rs.getString("action"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("result"), rs.getString("correlation_id"), rs.getString("source_ip"),
                rs.getString("user_agent"), rs.getString("metadata")),
                values.toArray());
    }

    private void add(StringBuilder sql, List<Object> values, String column, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" = ?");
        values.add(value);
    }

    public record AuditRow(UUID id, OffsetDateTime occurredAt, UUID actorId, String actorName,
                           String actorRole, String action, String resourceType, UUID resourceId,
                           String result, String correlationId, String sourceIp, String userAgent,
                           String metadata) { }
}
