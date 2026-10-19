package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;
import ec.gob.simertpi.api.parking.dto.AdminParkingSessionPage;
import ec.gob.simertpi.api.parking.dto.AdminParkingSessionView;
import ec.gob.simertpi.domain.parking.entity.ParkingSessionStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
public class AdminParkingSessionQueryService {
    private static final ZoneId MUNICIPAL_ZONE = ZoneId.of("America/Guayaquil");
    private static final String JOINS = """
            FROM parking.parking_sessions s
            JOIN identity.vehicles v ON v.id = s.vehicle_id
            JOIN parking.parking_spaces p ON p.id = s.parking_space_id
            JOIN parking.streets st ON st.id = p.street_id
            JOIN parking.zones z ON z.id = st.zone_id
            """;
    private static final String SELECT_PREFIX = """
            SELECT s.id, v.plate, z.code AS zone_code, z.name AS zone_name,
                   st.code AS street_code, st.name AS street_name,
                   p.code AS space_code, p.space_number, p.active AS space_enabled,
                   s.status AS session_status, s.started_at, s.expected_end_at, s.ended_at,
                   GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                       (COALESCE(s.ended_at, CURRENT_TIMESTAMP) - s.started_at)) / 60))::bigint AS duration_minutes,
                   latest_payment.status AS payment_status
            """ + JOINS + """
            LEFT JOIN LATERAL (
                SELECT pay.status
                FROM payments.payments pay
                WHERE pay.parking_session_id = s.id
                ORDER BY pay.updated_at DESC, pay.created_at DESC, pay.id DESC
                LIMIT 1
            ) latest_payment ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminParkingSessionQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Audited(action = "ADMIN_PARKING_SESSIONS_SEARCHED", resourceType = "PARKING_SESSION")
    @Transactional(readOnly = true)
    public AdminParkingSessionPage search(int page, int size, ParkingSessionStatus status, String query,
                       LocalDate from, LocalDate to) {
        String normalized = query == null || query.isBlank() ? null : query.trim();
        if (normalized != null && normalized.length() > 100) throw new IllegalArgumentException("Search is too long");
        OffsetDateTime fromAt = from == null ? null : from.atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        OffsetDateTime toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        long offset = (long) page * size;
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder();
        if (status != null) {
            where.append(" AND s.status = :status");
            params.addValue("status", status.name(), Types.VARCHAR);
        }
        if (fromAt != null) {
            where.append(" AND s.started_at >= :fromAt");
            params.addValue("fromAt", fromAt, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (toExclusive != null) {
            where.append(" AND s.started_at < :toExclusive");
            params.addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (normalized != null) {
            where.append(" AND (s.id::text ILIKE :pattern OR v.plate ILIKE :pattern OR p.code ILIKE :pattern")
                    .append(" OR p.space_number ILIKE :pattern OR st.code ILIKE :pattern OR st.name ILIKE :pattern")
                    .append(" OR z.code ILIKE :pattern OR z.name ILIKE :pattern)");
            params.addValue("pattern", "%" + normalized.toLowerCase(Locale.ROOT) + "%", Types.VARCHAR);
        }
        String predicate = where.isEmpty() ? "" : " WHERE " + where.substring(5);
        long total = jdbc.queryForObject("SELECT COUNT(*) " + JOINS + predicate, params, Long.class);
        List<AdminParkingSessionView> items = jdbc.query(SELECT_PREFIX + predicate
                        + " ORDER BY s.started_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", size).addValue("offset", offset), ROW_MAPPER);
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
        return new AdminParkingSessionPage(items, page, size, total, totalPages,
                page == 0, total == 0 || page + 1 >= totalPages);
    }

    private static final RowMapper<AdminParkingSessionView> ROW_MAPPER = (rs, row) ->
            new AdminParkingSessionView(rs.getObject("id", java.util.UUID.class), rs.getString("plate"),
                    rs.getString("zone_code"), rs.getString("zone_name"), rs.getString("street_code"),
                    rs.getString("street_name"), rs.getString("space_code"), rs.getString("space_number"),
                    rs.getBoolean("space_enabled"), rs.getString("session_status"),
                    rs.getObject("started_at", OffsetDateTime.class),
                    rs.getObject("expected_end_at", OffsetDateTime.class),
                    rs.getObject("ended_at", OffsetDateTime.class), rs.getLong("duration_minutes"),
                    rs.getString("payment_status"));

}
