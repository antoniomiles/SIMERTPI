package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Database-backed same-plate/same-space cooldown after a completed 240-minute contract. */
@Service
public class ParkingReentryConfigurationService {
    public record Settings(int cooldownMinutes, OffsetDateTime updatedAt) { }

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditService audit;

    public ParkingReentryConfigurationService(JdbcTemplate jdbc, UserRepository users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Settings current() {
        return jdbc.queryForObject("SELECT cooldown_minutes, updated_at FROM configuration.parking_reentry_settings WHERE id=1",
                (rs, row) -> new Settings(rs.getInt("cooldown_minutes"), rs.getObject("updated_at", OffsetDateTime.class)));
    }

    @Transactional
    public Settings update(String username, int cooldownMinutes) {
        if (cooldownMinutes < 1 || cooldownMinutes > 1440) {
            throw new IllegalArgumentException("cooldownMinutes must be between 1 and 1440");
        }
        var admin = users.findByUsernameWithRoles(username).orElseThrow(ForbiddenException::new);
        if (!admin.isEnabled() || admin.getRoles().stream().noneMatch(role ->
                "SIMERTPI_ADMIN".equals(role.getCode()) || "IT_ADMIN".equals(role.getCode()))) {
            throw new ForbiddenException();
        }
        Settings before = currentForUpdate();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("UPDATE configuration.parking_reentry_settings SET cooldown_minutes=?, updated_at=?, updated_by=? WHERE id=1",
                cooldownMinutes, now, admin.getId());
        audit.success("PARKING_REENTRY_COOLDOWN_CHANGED", "PARKING_CONFIGURATION",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), null,
                Map.of("previousMinutes", before.cooldownMinutes(), "cooldownMinutes", cooldownMinutes));
        return new Settings(cooldownMinutes, now);
    }

    /** Serializes configuration update against the value read for an update. */
    private Settings currentForUpdate() {
        return jdbc.queryForObject("SELECT cooldown_minutes, updated_at FROM configuration.parking_reentry_settings WHERE id=1 FOR UPDATE",
                (rs, row) -> new Settings(rs.getInt("cooldown_minutes"), rs.getObject("updated_at", OffsetDateTime.class)));
    }

    /** Serialize creation and closure for the normalized plate + space identity. */
    @Transactional
    public void lockVehicleSpace(UUID vehicleId, UUID spaceId) {
        String plate = jdbc.queryForObject(
                "SELECT upper(btrim(plate)) FROM identity.vehicles WHERE id=?",
                String.class, vehicleId);
        if (plate == null || plate.isBlank()) throw new IllegalStateException("Vehicle plate is missing");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                "PARKING_REENTRY:" + plate + ":" + spaceId);
    }

    @Transactional(readOnly = true)
    public OffsetDateTime blockedUntil(UUID vehicleId, UUID spaceId, OffsetDateTime now) {
        Integer cooldown = jdbc.queryForObject("SELECT cooldown_minutes FROM configuration.parking_reentry_settings WHERE id=1 FOR SHARE", Integer.class);
        if (cooldown == null) throw new IllegalStateException("Parking reentry setting is missing");
        var latest = jdbc.query("""
                SELECT s.ended_at
                  FROM parking.parking_sessions s
                  JOIN identity.vehicles v ON v.id=s.vehicle_id
                 WHERE upper(btrim(v.plate))=(SELECT upper(btrim(plate)) FROM identity.vehicles WHERE id=?)
                   AND s.parking_space_id=? AND s.status='COMPLETED'
                   AND s.ended_at IS NOT NULL
                   AND s.expected_end_at >= s.started_at + INTERVAL '240 minutes'
                 ORDER BY s.ended_at DESC, s.id DESC LIMIT 1
                """, (rs, row) -> rs.getObject("ended_at", OffsetDateTime.class), vehicleId, spaceId);
        if (latest.isEmpty()) return null;
        OffsetDateTime until = latest.getFirst().plusMinutes(cooldown);
        return now.isBefore(until) ? until : null;
    }
}
