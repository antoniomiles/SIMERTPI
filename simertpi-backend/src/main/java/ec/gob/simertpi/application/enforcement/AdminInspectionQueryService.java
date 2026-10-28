package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminInspectionDetail;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminInspectionItem;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminInspectionPage;
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
public class AdminInspectionQueryService {
    private static final ZoneId MUNICIPAL_ZONE = ZoneId.of("America/Guayaquil");
    private static final String FROM = """
            FROM enforcement.inspections i
            JOIN identity.users inspector ON inspector.id = i.inspector_id
            JOIN parking.parking_spaces ps ON ps.id = i.parking_space_id
            JOIN parking.streets st ON st.id = ps.street_id
            JOIN parking.zones z ON z.id = st.zone_id
            LEFT JOIN LATERAL (
                SELECT candidate.id
                FROM enforcement.violations candidate
                WHERE candidate.inspection_id = i.id
                ORDER BY candidate.created_at DESC, candidate.id DESC
                LIMIT 1
            ) v ON TRUE
            """;
    private static final String SELECT = """
            SELECT i.id, i.observed_at, i.result, i.observed_plate AS plate,
                   z.code AS zone_code, z.name AS zone_name,
                   st.code AS street_code, st.name AS street_name,
                   ps.code AS space_code, ps.space_number,
                   inspector.username AS inspector_username, v.id AS violation_id
            """ + FROM;
    private static final RowMapper<AdminInspectionItem> ITEM_MAPPER = (rs, row) -> new AdminInspectionItem(
            rs.getObject("id", UUID.class), rs.getObject("observed_at", OffsetDateTime.class),
            rs.getString("result"), rs.getString("plate"), rs.getString("zone_code"),
            rs.getString("zone_name"), rs.getString("street_code"), rs.getString("street_name"),
            rs.getString("space_code"), rs.getString("space_number"), rs.getString("inspector_username"),
            rs.getObject("violation_id", UUID.class));
    private static final RowMapper<AdminInspectionDetail> DETAIL_MAPPER = (rs, row) -> new AdminInspectionDetail(
            rs.getObject("id", UUID.class), rs.getObject("observed_at", OffsetDateTime.class),
            rs.getString("result"), rs.getString("plate"), rs.getString("zone_code"),
            rs.getString("zone_name"), rs.getString("street_code"), rs.getString("street_name"),
            rs.getString("space_code"), rs.getString("space_number"), rs.getString("inspector_username"),
            rs.getObject("violation_id", UUID.class), rs.getString("notes"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"));

    private final NamedParameterJdbcTemplate jdbc;

    public AdminInspectionQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Audited(action = "ADMIN_INSPECTIONS_SEARCHED", resourceType = "INSPECTION")
    @Transactional(readOnly = true)
    public AdminInspectionPage search(int page, int size, String plate, String result, String zone,
                                      LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (plate != null && !plate.isBlank()) {
            where.append(" AND i.observed_plate ILIKE :plate");
            params.addValue("plate", "%" + plate.trim().toUpperCase(Locale.ROOT) + "%", Types.VARCHAR);
        }
        if (result != null) {
            where.append(" AND i.result = :result");
            params.addValue("result", result, Types.VARCHAR);
        }
        if (zone != null && !zone.isBlank()) {
            where.append(" AND (z.code ILIKE :zone OR z.name ILIKE :zone)");
            params.addValue("zone", "%" + zone.trim() + "%", Types.VARCHAR);
        }
        OffsetDateTime fromAt = from == null ? null : from.atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        OffsetDateTime toExclusive = to == null ? null
                : to.plusDays(1).atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        if (fromAt != null) {
            where.append(" AND i.observed_at >= :fromAt");
            params.addValue("fromAt", fromAt, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (toExclusive != null) {
            where.append(" AND i.observed_at < :toExclusive");
            params.addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE);
        }

        String predicate = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + predicate, params, Long.class);
        long offset = (long) page * size;
        List<AdminInspectionItem> items = jdbc.query(SELECT + predicate
                        + " ORDER BY i.observed_at DESC, i.id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource(params.getValues()).addValue("limit", size).addValue("offset", offset),
                ITEM_MAPPER);
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
        return new AdminInspectionPage(items, page, size, total, totalPages, page == 0,
                total == 0 || page + 1 >= totalPages);
    }

    @Audited(action = "ADMIN_INSPECTION_VIEWED", resourceType = "INSPECTION", resourceIdArgument = 0)
    @Transactional(readOnly = true)
    public AdminInspectionDetail findById(UUID id) {
        String detailSelect = """
                SELECT i.id, i.observed_at, i.result, i.observed_plate AS plate,
                       z.code AS zone_code, z.name AS zone_name,
                       st.code AS street_code, st.name AS street_name,
                       ps.code AS space_code, ps.space_number,
                       inspector.username AS inspector_username, v.id AS violation_id,
                       LEFT(i.notes, 250) AS notes, i.created_at, i.latitude, i.longitude
                """ + FROM;
        return jdbc.query(detailSelect + " WHERE i.id = :id", new MapSqlParameterSource("id", id), DETAIL_MAPPER)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
    }
}
