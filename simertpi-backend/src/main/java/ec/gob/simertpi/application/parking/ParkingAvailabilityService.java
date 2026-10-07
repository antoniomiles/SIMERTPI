package ec.gob.simertpi.application.parking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

/** Read-only operational projection. Never includes occupant, vehicle or payment identifiers. */
@Service
public class ParkingAvailabilityService {
    private final JdbcTemplate jdbc;
    private final long threshold;
    public ParkingAvailabilityService(JdbcTemplate jdbc,
            @Value("${simertpi.parking.availability.ending-soon-seconds:600}") long threshold) {
        if (threshold < 0) throw new IllegalArgumentException("Invalid ending-soon threshold");
        this.jdbc = jdbc; this.threshold = threshold;
    }
    public record Availability(UUID parkingSpaceId, UUID streetId, UUID zoneId, String spaceCode,
            boolean active, String operationalStatus, boolean selectable, OffsetDateTime expectedEndAt,
            Long remainingSeconds, BigDecimal latitude, BigDecimal longitude, Instant evaluatedAt, long endingSoonSeconds) { }
    @Transactional(readOnly=true)
    public List<Availability> list() {
        Instant now = Instant.now();
        // Yellow starts when the first configured expiration reminder becomes due.
        long effectiveThreshold = endingSoonSeconds();
        // One statement gives list/map the same database snapshot. V21 enforces one occupant per space.
        return jdbc.query("""
            SELECT p.id,p.street_id,t.zone_id,p.code,p.active,
                   (p.active AND t.active AND z.active) AS enabled,
                   s.status,s.expected_end_at,p.latitude,p.longitude
              FROM parking.parking_spaces p
              JOIN parking.streets t ON t.id=p.street_id
              JOIN parking.zones z ON z.id=t.zone_id
              LEFT JOIN parking.parking_sessions s ON s.parking_space_id=p.id
                AND s.status IN ('PENDING_PAYMENT','ACTIVE','EXTENDED','EXPIRED','MAX_TIME_REACHED')
             ORDER BY p.code
            """, (r,n) -> project(r.getObject("id",UUID.class),r.getObject("street_id",UUID.class),
                r.getObject("zone_id",UUID.class),r.getString("code"),r.getBoolean("active"),
                r.getBoolean("enabled"),r.getString("status"),r.getObject("expected_end_at",OffsetDateTime.class),
                r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),now,effectiveThreshold));
    }
    public long endingSoonSeconds() {
        Long reminderSeconds = jdbc.queryForObject("SELECT max(minutes_before)::bigint * 60 FROM configuration.notification_rules WHERE event_type IN ('EXPIRATION','PARKING_ENDING_SOON') AND enabled=true AND minutes_before>0 AND valid_from<=CURRENT_TIMESTAMP AND (valid_to IS NULL OR valid_to>=CURRENT_TIMESTAMP)", Long.class);
        return reminderSeconds == null ? threshold : reminderSeconds;
    }
    public static Availability project(UUID id, UUID street, UUID zone, String code, boolean active,
            boolean enabled, String state, OffsetDateTime end, BigDecimal lat, BigDecimal lon, Instant now, long threshold) {
        boolean occupant = state != null;
        boolean revealTime = occupant && !"PENDING_PAYMENT".equals(state);
        Long remaining = revealTime && end != null ? Math.max(0, Duration.between(now,end.toInstant()).getSeconds()) : null;
        String status = !enabled ? "DISABLED" : !occupant ? "AVAILABLE" : "OCCUPIED";
        if (enabled && List.of("ACTIVE","EXTENDED").contains(state == null ? "" : state)
                && remaining != null && remaining > 0 && remaining <= threshold) status="ENDING_SOON";
        return new Availability(id,street,zone,code,active,status,"AVAILABLE".equals(status),
                revealTime ? end : null,remaining,lat,lon,now,threshold);
    }
}
