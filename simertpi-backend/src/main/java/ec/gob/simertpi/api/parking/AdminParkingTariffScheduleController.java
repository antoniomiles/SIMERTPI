package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.ParkingConfigurationCatalogService;
import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.entity.Zone;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/parking")
public class AdminParkingTariffScheduleController {
    private final ParkingConfigurationCatalogService catalog;
    private final JdbcTemplate jdbc;

    public AdminParkingTariffScheduleController(ParkingConfigurationCatalogService catalog, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @GetMapping("/tariffs")
    public ResponseEntity<List<TariffView>> tariffs() {
        Map<UUID, Zone> zones = zoneIndex();
        Map<UUID, String> cancellationActors = jdbc.query("""
                SELECT t.id,u.username FROM parking.tariffs t
                JOIN identity.users u ON u.id=t.cancelled_by_user_id
                WHERE t.cancelled_by_user_id IS NOT NULL
                """, rs -> {
            Map<UUID, String> result = new java.util.HashMap<>();
            while (rs.next()) result.put(rs.getObject("id", UUID.class), rs.getString("username"));
            return result;
        });
        var rows = catalog.findTariffs().stream()
                .sorted(Comparator.comparing(Tariff::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(t -> new TariffView(t.getId(), t.getCode(), t.getName(), t.getZoneId(), zoneCode(zones, t.getZoneId()),
                        zoneName(zones, t.getZoneId()), t.getCurrency(), t.getAmount(), t.getDurationMinutes(),
                        t.getMinMinutes(), t.getMaxContinuousMinutes(), t.getGracePeriodMinutes(), t.getRoundingMode(),
                        t.getNormativeReference(), t.isActive(), t.getValidFrom(), t.getValidTo(), t.getCreatedAt(), t.getUpdatedAt(),
                        t.getCancelledAt(), t.getCancelledByUserId(), t.getCancellationReason(), cancellationActors.get(t.getId()),
                        t.getRowVersion()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    @GetMapping("/schedules")
    public ResponseEntity<List<ScheduleView>> schedules() {
        Map<UUID, Zone> zones = zoneIndex();
        var rows = catalog.findSchedules().stream()
                .sorted(Comparator.comparing((Schedule s) -> s.getZoneId() == null ? "" : String.valueOf(s.getZoneId()))
                        .thenComparing(Schedule::getDayOfWeek).thenComparing(Schedule::getStartTime))
                .map(s -> new ScheduleView(s.getId(), s.getZoneId(), zoneCode(zones, s.getZoneId()),
                        zoneName(zones, s.getZoneId()), s.getDayOfWeek(), s.getStartTime(), s.getEndTime(),
                        s.isActive(), s.getValidFrom(), s.getValidTo(), s.getCreatedAt(), s.getUpdatedAt(), s.getRowVersion()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    @GetMapping("/calendar-exceptions")
    public ResponseEntity<List<CalendarExceptionView>> calendarExceptions() {
        Map<UUID, Zone> zones = zoneIndex();
        var rows = catalog.findCalendarExceptions().stream()
                .sorted(Comparator.comparing(Holiday::getHolidayDate).thenComparing(Holiday::getName))
                .map(h -> new CalendarExceptionView(h.getId(), h.getHolidayDate(), h.getName(), h.getZoneId(),
                        zoneCode(zones, h.getZoneId()), zoneName(zones, h.getZoneId()), h.getHolidayType(),
                        h.isTariffed(), h.getStartTime(), h.getEndTime(), h.getNormativeReference(),
                        h.getValidFrom(), h.getValidTo(), h.isActive(), h.getCreatedAt(), h.getUpdatedAt()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    private Map<UUID, Zone> zoneIndex() {
        return catalog.findZones().stream().collect(Collectors.toMap(Zone::getId, Function.identity()));
    }

    private static String zoneCode(Map<UUID, Zone> zones, UUID zoneId) {
        return zoneId == null ? null : zones.containsKey(zoneId) ? zones.get(zoneId).getCode() : null;
    }

    private static String zoneName(Map<UUID, Zone> zones, UUID zoneId) {
        return zoneId == null ? null : zones.containsKey(zoneId) ? zones.get(zoneId).getName() : null;
    }

    public record TariffView(UUID id, String code, String name, UUID zoneId, String zoneCode, String zoneName,
            String currency, BigDecimal amount, Integer durationMinutes, Integer minMinutes,
            Integer maxContinuousMinutes, Integer gracePeriodMinutes, String roundingMode, String normativeReference, boolean active,
            OffsetDateTime validFrom, OffsetDateTime validTo, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime cancelledAt, UUID cancelledByUserId, String cancellationReason, String cancelledByUsername,
            Long rowVersion) { }

    public record ScheduleView(UUID id, UUID zoneId, String zoneCode, String zoneName, Short dayOfWeek,
            LocalTime startTime, LocalTime endTime, boolean active, LocalDate validFrom, LocalDate validTo,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, long rowVersion) { }

    public record CalendarExceptionView(UUID id, LocalDate holidayDate, String name, UUID zoneId, String zoneCode,
            String zoneName, String holidayType, boolean tariffed, LocalTime startTime, LocalTime endTime,
            String normativeReference, LocalDate validFrom, LocalDate validTo, boolean active,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
}
