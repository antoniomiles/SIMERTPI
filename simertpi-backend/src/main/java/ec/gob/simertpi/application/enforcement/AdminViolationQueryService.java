package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminViolationItem;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminViolationPage;
import ec.gob.simertpi.application.audit.Audited;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminViolationQueryService {
    private static final ZoneId MUNICIPAL_ZONE = ZoneId.of("America/Guayaquil");
    private static final String FROM = """
            FROM enforcement.violations v
            JOIN enforcement.inspections i ON i.id = v.inspection_id
            JOIN identity.users inspector ON inspector.id = v.inspector_id
            JOIN parking.parking_spaces ps ON ps.id = v.parking_space_id
            JOIN parking.streets st ON st.id = ps.street_id
            JOIN parking.zones z ON z.id = st.zone_id
            """;
    private static final String SELECT = """
            SELECT v.id, v.plate, v.violation_type, v.status, v.occurred_at,
                   v.inspection_id, v.parking_session_id,
                   z.code AS zone_code, z.name AS zone_name,
                   st.code AS street_code, st.name AS street_name,
                   ps.code AS space_code, ps.space_number,
                   inspector.username AS inspector_username,
                   (SELECT COUNT(*) FROM enforcement.evidence e WHERE e.violation_id = v.id) AS evidence_count,
                   v.created_at, v.notified_at, v.paid_at
            """ + FROM;

    private static final RowMapper<AdminViolationItem> ROW_MAPPER = (rs, row) -> new AdminViolationItem(
            rs.getObject("id", UUID.class),
            rs.getString("plate"),
            rs.getString("violation_type"),
            rs.getString("status"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getObject("inspection_id", UUID.class),
            rs.getObject("parking_session_id", UUID.class),
            rs.getString("zone_code"),
            rs.getString("zone_name"),
            rs.getString("street_code"),
            rs.getString("street_name"),
            rs.getString("space_code"),
            rs.getString("space_number"),
            rs.getString("inspector_username"),
            rs.getLong("evidence_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("notified_at", OffsetDateTime.class),
            rs.getObject("paid_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public AdminViolationQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Audited(action = "ADMIN_VIOLATIONS_SEARCHED", resourceType = "VIOLATION")
    @Transactional(readOnly = true)
    public AdminViolationPage search(int page, int size, String status, String plate,
                                     String zone, String query, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            where.append(" AND v.status = :status");
            params.addValue("status", status, Types.VARCHAR);
        }
        if (plate != null && !plate.isBlank()) {
            where.append(" AND v.plate ILIKE :plate");
            params.addValue("plate", "%" + plate.trim().toUpperCase(Locale.ROOT) + "%", Types.VARCHAR);
        }
        if (zone != null && !zone.isBlank()) {
            where.append(" AND (z.code ILIKE :zone OR z.name ILIKE :zone)");
            params.addValue("zone", "%" + zone.trim() + "%", Types.VARCHAR);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (v.id::text ILIKE :query OR v.plate ILIKE :query OR v.violation_type ILIKE :query")
                    .append(" OR z.code ILIKE :query OR z.name ILIKE :query OR st.code ILIKE :query")
                    .append(" OR st.name ILIKE :query OR ps.code ILIKE :query OR ps.space_number ILIKE :query")
                    .append(" OR inspector.username ILIKE :query)");
            params.addValue("query", "%" + query.trim() + "%", Types.VARCHAR);
        }
        OffsetDateTime fromAt = from == null ? null : from.atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        OffsetDateTime toExclusive = to == null ? null
                : to.plusDays(1).atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        if (fromAt != null) {
            where.append(" AND v.occurred_at >= :fromAt");
            params.addValue("fromAt", fromAt, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (toExclusive != null) {
            where.append(" AND v.occurred_at < :toExclusive");
            params.addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE);
        }

        String predicate = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + predicate, params, Long.class);
        long offset = (long) page * size;
        List<AdminViolationItem> items = jdbc.query(SELECT + predicate
                        + " ORDER BY v.occurred_at DESC, v.id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource(params.getValues()).addValue("limit", size).addValue("offset", offset),
                ROW_MAPPER);
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
        return new AdminViolationPage(items, page, size, total, totalPages, page == 0,
                total == 0 || page + 1 >= totalPages);
    }

    @Audited(action = "ADMIN_VIOLATION_VIEWED", resourceType = "VIOLATION", resourceIdArgument = 0)
    @Transactional(readOnly = true)
    public AdminViolationItem findById(UUID id) {
        return jdbc.query(SELECT + " WHERE v.id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
    }
}
