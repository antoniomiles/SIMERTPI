package ec.gob.simertpi.application.parking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resolves weekly billing rules separately from parking operating hours. */
@Service
public class BillingDayCalendarService {
    private final JdbcTemplate jdbc;
    public BillingDayCalendarService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Policy resolve(UUID zoneId, LocalDate date) {
        if (date == null) throw new IllegalArgumentException("BILLING_DATE_REQUIRED");
        List<Policy> rows = jdbc.query("""
                SELECT c.id,c.zone_id,c.normative_reference,d.chargeable
                  FROM parking.billing_day_calendars c
                  JOIN parking.billing_day_calendar_days d ON d.calendar_id=c.id
                 WHERE c.active AND c.valid_from<=? AND (c.valid_to IS NULL OR c.valid_to>=?)
                   AND (c.zone_id=? OR c.zone_id IS NULL) AND d.day_of_week=?
                 ORDER BY (c.zone_id IS NOT NULL) DESC,c.valid_from DESC,c.id
                """, (rs, row) -> new Policy(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
                rs.getString("normative_reference"), rs.getBoolean("chargeable"), true),
                date, date, zoneId, date.getDayOfWeek().getValue());
        if (rows.isEmpty()) return new Policy(null, null, null, true, false);
        Policy selected = rows.getFirst();
        if (rows.size() > 1 && Objects.equals(selected.zoneId(), rows.get(1).zoneId()))
            throw new IllegalStateException("BILLING_CALENDAR_CONFIGURATION_INVALID");
        return selected;
    }

    public record Policy(UUID calendarId, UUID zoneId, String normativeReference, boolean chargeable, boolean configured) { }
}
