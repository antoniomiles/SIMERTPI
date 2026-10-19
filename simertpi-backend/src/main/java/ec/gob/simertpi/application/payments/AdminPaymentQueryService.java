package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.api.payments.admin.dto.AdminPaymentCurrencySummary;
import ec.gob.simertpi.api.payments.admin.dto.AdminPaymentItem;
import ec.gob.simertpi.api.payments.admin.dto.AdminPaymentPage;
import ec.gob.simertpi.application.audit.Audited;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class AdminPaymentQueryService {
    private static final ZoneId MUNICIPAL_ZONE = ZoneId.of("America/Guayaquil");
    private static final String FROM = "FROM payments.payments p";

    private final NamedParameterJdbcTemplate jdbc;

    public AdminPaymentQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Audited(action = "ADMIN_PAYMENTS_SEARCHED", resourceType = "PAYMENT")
    @Transactional(readOnly = true)
    public AdminPaymentPage search(int page, int size, String status, String query,
                                   LocalDate from, LocalDate to) {
        String normalized = query == null || query.isBlank() ? null : query.trim();
        OffsetDateTime fromAt = from == null ? null : from.atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        OffsetDateTime toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(MUNICIPAL_ZONE).toOffsetDateTime();
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder predicate = new StringBuilder(" WHERE 1=1");

        if (status != null) {
            predicate.append(" AND p.status = :status");
            params.addValue("status", status, Types.VARCHAR);
        }
        if (fromAt != null) {
            predicate.append(" AND p.created_at >= :fromAt");
            params.addValue("fromAt", fromAt, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (toExclusive != null) {
            predicate.append(" AND p.created_at < :toExclusive");
            params.addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (normalized != null) {
            predicate.append(" AND (p.id::text ILIKE :pattern OR p.parking_session_id::text ILIKE :pattern")
                    .append(" OR p.provider_transaction_id ILIKE :pattern OR p.provider ILIKE :pattern)");
            params.addValue("pattern", "%" + normalized + "%", Types.VARCHAR);
        }

        String filter = predicate.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + filter, params, Long.class);
        long offset = (long) page * size;
        List<AdminPaymentItem> items = jdbc.query("""
                SELECT p.id, p.parking_session_id, p.provider, p.amount, p.currency, p.status,
                       p.created_at, p.paid_at
                """ + FROM + filter + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource(params.getValues()).addValue("limit", size).addValue("offset", offset),
                (rs, row) -> new AdminPaymentItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("parking_session_id", UUID.class),
                        rs.getString("provider"),
                        rs.getBigDecimal("amount").toPlainString(),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("paid_at", OffsetDateTime.class)));

        List<AdminPaymentCurrencySummary> summaries = jdbc.query("""
                SELECT p.currency,
                       COUNT(*) FILTER (WHERE p.status = 'APPROVED') AS approved_count,
                       COALESCE(SUM(p.amount) FILTER (WHERE p.status = 'APPROVED'), 0) AS approved_amount,
                       COUNT(*) FILTER (WHERE p.status IN ('PENDING', 'PROCESSING')) AS pending_count,
                       COALESCE(SUM(p.amount) FILTER (WHERE p.status IN ('PENDING', 'PROCESSING')), 0) AS pending_amount,
                       COUNT(*) FILTER (WHERE p.status IN ('DECLINED', 'FAILED')) AS failed_count,
                       COALESCE(SUM(p.amount) FILTER (WHERE p.status IN ('DECLINED', 'FAILED')), 0) AS failed_amount,
                       COUNT(*) FILTER (WHERE p.status = 'REFUNDED') AS refunded_count,
                       COALESCE(SUM(p.amount) FILTER (WHERE p.status = 'REFUNDED'), 0) AS refunded_amount,
                       COUNT(*) FILTER (WHERE p.status = 'CANCELLED') AS cancelled_count,
                       COALESCE(SUM(p.amount) FILTER (WHERE p.status = 'CANCELLED'), 0) AS cancelled_amount
                """ + FROM + filter + " GROUP BY p.currency ORDER BY p.currency",
                params,
                (rs, row) -> new AdminPaymentCurrencySummary(
                        rs.getString("currency"),
                        rs.getLong("approved_count"), decimal(rs.getBigDecimal("approved_amount")),
                        rs.getLong("pending_count"), decimal(rs.getBigDecimal("pending_amount")),
                        rs.getLong("failed_count"), decimal(rs.getBigDecimal("failed_amount")),
                        rs.getLong("refunded_count"), decimal(rs.getBigDecimal("refunded_amount")),
                        rs.getLong("cancelled_count"), decimal(rs.getBigDecimal("cancelled_amount"))));

        int totalPages = total == 0 ? 0
                : Math.toIntExact(total / size + (total % size == 0 ? 0 : 1));
        return new AdminPaymentPage(items, summaries, page, size, total, totalPages,
                page == 0, page + 1 >= totalPages);
    }

    private static String decimal(BigDecimal value) {
        return value.toPlainString();
    }
}
