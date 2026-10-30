package ec.gob.simertpi.application.reports;

import ec.gob.simertpi.api.reports.admin.dto.AdminReportResponse;
import ec.gob.simertpi.application.audit.Audited;
import org.springframework.beans.factory.annotation.Value;
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

@Service
public class AdminReportsQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ZoneId timeZone;

    public AdminReportsQueryService(NamedParameterJdbcTemplate jdbc,
                                    @Value("${simertpi.parking.rules.time-zone:America/Guayaquil}") String timeZone) {
        this.jdbc = jdbc;
        this.timeZone = ZoneId.of(timeZone);
    }

    @Audited(action = "ADMIN_REPORTS_VIEWED", resourceType = "ADMIN_REPORT")
    @Transactional(readOnly = true)
    public AdminReportResponse load(LocalDate from, LocalDate to) {
        return load(from, to, true);
    }

    @Audited(action = "ADMIN_REPORTS_VIEWED", resourceType = "ADMIN_REPORT")
    @Transactional(readOnly = true)
    public AdminReportResponse load(LocalDate from, LocalDate to, boolean includeFinancial) {
        OffsetDateTime fromAt = from.atStartOfDay(timeZone).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(timeZone).toOffsetDateTime();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromAt", fromAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toExclusive", toExclusive, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("fromDate", from, Types.DATE)
                .addValue("toDate", to, Types.DATE)
                .addValue("timeZone", timeZone.getId(), Types.VARCHAR);

        long sessionsStarted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM parking.parking_sessions s
                WHERE s.started_at >= :fromAt AND s.started_at < :toExclusive
                """, params, Long.class);
        List<AdminReportResponse.CountByCode> sessionsByStatus = jdbc.query("""
                SELECT s.status, COUNT(*) AS item_count
                FROM parking.parking_sessions s
                WHERE s.started_at >= :fromAt AND s.started_at < :toExclusive
                GROUP BY s.status ORDER BY s.status
                """, params, (rs, row) -> new AdminReportResponse.CountByCode(
                rs.getString("status"), rs.getLong("item_count")));
        List<AdminReportResponse.ZoneSessionCount> sessionsByZone = jdbc.query("""
                SELECT z.code, z.name, COUNT(*) AS item_count
                FROM parking.parking_sessions s
                JOIN parking.parking_spaces p ON p.id = s.parking_space_id
                JOIN parking.streets st ON st.id = p.street_id
                JOIN parking.zones z ON z.id = st.zone_id
                WHERE s.started_at >= :fromAt AND s.started_at < :toExclusive
                GROUP BY z.id, z.code, z.name ORDER BY z.code
                """, params, (rs, row) -> new AdminReportResponse.ZoneSessionCount(
                rs.getString("code"), rs.getString("name"), rs.getLong("item_count")));
        List<AdminReportResponse.DailySessionCount> dailySessions = jdbc.query("""
                WITH days AS (
                    SELECT CAST(:fromDate AS date) + day_offset AS report_date
                    FROM generate_series(0, CAST(:toDate AS date) - CAST(:fromDate AS date)) AS series(day_offset)
                ), session_days AS (
                    SELECT (s.started_at AT TIME ZONE :timeZone)::date AS report_date
                    FROM parking.parking_sessions s
                    WHERE s.started_at >= :fromAt AND s.started_at < :toExclusive
                ), daily AS (
                    SELECT report_date, COUNT(*) AS item_count
                    FROM session_days GROUP BY report_date
                )
                SELECT days.report_date, COALESCE(daily.item_count, 0) AS item_count
                FROM days LEFT JOIN daily USING (report_date) ORDER BY days.report_date
                """, params, (rs, row) -> new AdminReportResponse.DailySessionCount(
                rs.getObject("report_date", LocalDate.class), rs.getLong("item_count")));

        AdminReportResponse.RecordedDuration recordedDuration = jdbc.queryForObject("""
                SELECT COUNT(*) AS ended_count,
                       COALESCE(SUM(EXTRACT(EPOCH FROM (s.ended_at - s.started_at))), 0) AS total_seconds,
                       AVG(EXTRACT(EPOCH FROM (s.ended_at - s.started_at))) AS average_seconds
                FROM parking.parking_sessions s
                WHERE s.started_at >= :fromAt AND s.started_at < :toExclusive
                  AND s.ended_at IS NOT NULL AND s.ended_at >= s.started_at
                """, params, (rs, row) -> new AdminReportResponse.RecordedDuration(
                rs.getLong("ended_count"), decimal(rs.getBigDecimal("total_seconds")),
                nullableDecimal(rs.getBigDecimal("average_seconds"))));
        List<AdminReportResponse.ZoneSpaceCount> spacesByZone = jdbc.query("""
                SELECT z.code, z.name, COUNT(p.id) AS configured_spaces,
                       COUNT(p.id) FILTER (WHERE p.active) AS active_spaces
                FROM parking.zones z
                LEFT JOIN parking.streets st ON st.zone_id = z.id
                LEFT JOIN parking.parking_spaces p ON p.street_id = st.id
                GROUP BY z.id, z.code, z.name ORDER BY z.code
                """, params, (rs, row) -> new AdminReportResponse.ZoneSpaceCount(
                rs.getString("code"), rs.getString("name"), rs.getLong("configured_spaces"),
                rs.getLong("active_spaces")));

        if (!includeFinancial) {
            return new AdminReportResponse(from, to, timeZone.getId(),
                    new AdminReportResponse.Operational(sessionsStarted, sessionsByStatus, sessionsByZone,
                            dailySessions, recordedDuration, spacesByZone),
                    new AdminReportResponse.Financial(List.of(), List.of(), List.of(), List.of()));
        }
        List<AdminReportResponse.PaymentStatusAmount> paymentsByCurrentStatus = jdbc.query("""
                SELECT p.status, p.currency, COUNT(*) AS item_count, SUM(p.amount) AS amount
                FROM payments.payments p
                WHERE p.created_at >= :fromAt AND p.created_at < :toExclusive
                GROUP BY p.status, p.currency ORDER BY p.status, p.currency
                """, params, (rs, row) -> new AdminReportResponse.PaymentStatusAmount(
                rs.getString("status"), rs.getString("currency"), rs.getLong("item_count"),
                decimal(rs.getBigDecimal("amount"))));
        List<AdminReportResponse.CurrencyAmountCount> approvedByPaidAt = jdbc.query("""
                SELECT p.currency, COUNT(*) AS item_count, SUM(p.amount) AS amount
                FROM payments.payments p
                WHERE p.status = 'APPROVED' AND p.paid_at >= :fromAt AND p.paid_at < :toExclusive
                GROUP BY p.currency ORDER BY p.currency
                """, params, (rs, row) -> new AdminReportResponse.CurrencyAmountCount(
                rs.getString("currency"), rs.getLong("item_count"), decimal(rs.getBigDecimal("amount"))));
        List<AdminReportResponse.DailyApprovedPayment> dailyApprovedPayments = jdbc.query("""
                WITH days AS (
                    SELECT CAST(:fromDate AS date) + day_offset AS report_date
                    FROM generate_series(0, CAST(:toDate AS date) - CAST(:fromDate AS date)) AS series(day_offset)
                ), currencies AS (
                    SELECT DISTINCT p.currency FROM payments.payments p
                    WHERE p.status = 'APPROVED' AND p.paid_at >= :fromAt AND p.paid_at < :toExclusive
                ), payment_days AS (
                    SELECT (p.paid_at AT TIME ZONE :timeZone)::date AS report_date, p.currency, p.amount
                    FROM payments.payments p
                    WHERE p.status = 'APPROVED' AND p.paid_at >= :fromAt AND p.paid_at < :toExclusive
                ), daily AS (
                    SELECT report_date, currency, COUNT(*) AS item_count, SUM(amount) AS amount
                    FROM payment_days GROUP BY report_date, currency
                )
                SELECT days.report_date, currencies.currency,
                       COALESCE(daily.item_count, 0) AS item_count,
                       COALESCE(daily.amount, 0) AS amount
                FROM days CROSS JOIN currencies
                LEFT JOIN daily ON daily.report_date = days.report_date AND daily.currency = currencies.currency
                ORDER BY currencies.currency, days.report_date
                """, params, (rs, row) -> new AdminReportResponse.DailyApprovedPayment(
                rs.getObject("report_date", LocalDate.class), rs.getString("currency"),
                rs.getLong("item_count"), decimal(rs.getBigDecimal("amount"))));
        List<AdminReportResponse.ZoneApprovedPayment> approvedByZone = jdbc.query("""
                SELECT z.code, z.name, p.currency, COUNT(*) AS item_count, SUM(p.amount) AS amount
                FROM payments.payments p
                JOIN parking.parking_sessions s ON s.id = p.parking_session_id
                JOIN parking.parking_spaces ps ON ps.id = s.parking_space_id
                JOIN parking.streets st ON st.id = ps.street_id
                JOIN parking.zones z ON z.id = st.zone_id
                WHERE p.status = 'APPROVED' AND p.paid_at >= :fromAt AND p.paid_at < :toExclusive
                GROUP BY z.id, z.code, z.name, p.currency ORDER BY z.code, p.currency
                """, params, (rs, row) -> new AdminReportResponse.ZoneApprovedPayment(
                rs.getString("code"), rs.getString("name"), rs.getString("currency"),
                rs.getLong("item_count"), decimal(rs.getBigDecimal("amount"))));

        return new AdminReportResponse(from, to, timeZone.getId(),
                new AdminReportResponse.Operational(sessionsStarted, sessionsByStatus, sessionsByZone,
                        dailySessions, recordedDuration, spacesByZone),
                new AdminReportResponse.Financial(paymentsByCurrentStatus, approvedByPaidAt,
                        dailyApprovedPayments, approvedByZone));
    }

    private static String decimal(BigDecimal value) {
        return value.setScale(Math.min(Math.max(value.scale(), 0), 6), java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String nullableDecimal(BigDecimal value) {
        return value == null ? null : decimal(value);
    }
}
