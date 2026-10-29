package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.api.parking.admin.dto.CalendarExceptionDeactivateRequest;
import ec.gob.simertpi.api.parking.admin.dto.CalendarExceptionWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleDeactivateRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleEndRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleWriteRequest;
import ec.gob.simertpi.application.parking.AdminOperationalCalendarService;
import ec.gob.simertpi.application.parking.calendar.ParkingCalendarService;
import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1/admin/parking")
public class AdminOperationalCalendarController {
    private final AdminOperationalCalendarService service;
    private final ZoneRepository zones;
    private final ParkingCalendarService calendar;

    public AdminOperationalCalendarController(AdminOperationalCalendarService service, ZoneRepository zones,
            ParkingCalendarService calendar) {
        this.service = service;
        this.zones = zones;
        this.calendar = calendar;
    }

    public record EffectiveCalendarView(UUID zoneId, LocalDate date, LocalTime time, boolean operational,
            boolean holiday, boolean chargeable, LocalTime windowStart, LocalTime windowEnd,
            String source, String reasonCode) { }

    @GetMapping("/schedules/effective")
    public ResponseEntity<EffectiveCalendarView> effectiveSchedule(@RequestParam UUID zoneId,
            @RequestParam LocalDate date, @RequestParam LocalTime time) {
        if (!zones.existsById(zoneId)) throw new ResponseStatusException(NOT_FOUND, "La zona seleccionada no existe.");
        var rules = calendar.resolve(zoneId, date, time);
        var window = rules.schedule();
        return noStore(new EffectiveCalendarView(zoneId, date, time, rules.operational(), rules.holiday(),
                rules.chargeable(), window == null ? null : window.startTime(),
                window == null ? null : window.endTime(), window == null ? "NONE" : window.source(), rules.reasonCode()));
    }

    @PostMapping("/schedules")
    public ResponseEntity<AdminParkingTariffScheduleController.ScheduleView> createSchedule(
            @Valid @RequestBody OperationalScheduleWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(scheduleView(service.createSchedule(request)));
    }

    @PutMapping("/schedules/{id}")
    public ResponseEntity<AdminParkingTariffScheduleController.ScheduleView> updateSchedule(
            @PathVariable UUID id, @Valid @RequestBody OperationalScheduleWriteRequest request) {
        return noStore(scheduleView(service.updateSchedule(id, request)));
    }

    @PostMapping("/schedules/{id}/revise")
    public ResponseEntity<AdminParkingTariffScheduleController.ScheduleView> reviseSchedule(
            @PathVariable UUID id, @Valid @RequestBody OperationalScheduleWriteRequest request) {
        return noStore(scheduleView(service.reviseCurrentSchedule(id, request)));
    }

    @PostMapping("/schedules/{id}/end")
    public ResponseEntity<AdminParkingTariffScheduleController.ScheduleView> endSchedule(
            @PathVariable UUID id, @Valid @RequestBody OperationalScheduleEndRequest request) {
        return noStore(scheduleView(service.endCurrentSchedule(id, request)));
    }

    @PostMapping("/schedules/{id}/deactivate")
    public ResponseEntity<AdminParkingTariffScheduleController.ScheduleView> deactivateSchedule(
            @PathVariable UUID id, @Valid @RequestBody OperationalScheduleDeactivateRequest request) {
        return noStore(scheduleView(service.deactivateSchedule(id, request.expectedUpdatedAt(), request.reason())));
    }

    @PostMapping("/calendar-exceptions")
    public ResponseEntity<AdminParkingTariffScheduleController.CalendarExceptionView> createException(
            @Valid @RequestBody CalendarExceptionWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(holidayView(service.createHoliday(request)));
    }

    @PutMapping("/calendar-exceptions/{id}")
    public ResponseEntity<AdminParkingTariffScheduleController.CalendarExceptionView> updateException(
            @PathVariable UUID id, @Valid @RequestBody CalendarExceptionWriteRequest request) {
        return noStore(holidayView(service.updateHoliday(id, request)));
    }

    @PostMapping("/calendar-exceptions/{id}/deactivate")
    public ResponseEntity<AdminParkingTariffScheduleController.CalendarExceptionView> deactivateException(
            @PathVariable UUID id, @Valid @RequestBody CalendarExceptionDeactivateRequest request) {
        return noStore(holidayView(service.deactivateHoliday(id, request.expectedUpdatedAt(), request.reason())));
    }

    private AdminParkingTariffScheduleController.ScheduleView scheduleView(Schedule value) {
        Zone zone = value.getZoneId() == null ? null : zones.findById(value.getZoneId()).orElse(null);
        return new AdminParkingTariffScheduleController.ScheduleView(value.getId(), value.getZoneId(),
                zone == null ? null : zone.getCode(), zone == null ? null : zone.getName(), value.getDayOfWeek(),
                value.getStartTime(), value.getEndTime(), value.isActive(), value.getValidFrom(), value.getValidTo(),
                value.getCreatedAt(), value.getUpdatedAt(), value.getRowVersion());
    }

    private AdminParkingTariffScheduleController.CalendarExceptionView holidayView(Holiday value) {
        Zone zone = value.getZoneId() == null ? null : zones.findById(value.getZoneId()).orElse(null);
        return new AdminParkingTariffScheduleController.CalendarExceptionView(value.getId(), value.getHolidayDate(),
                value.getName(), value.getZoneId(), zone == null ? null : zone.getCode(),
                zone == null ? null : zone.getName(), value.getHolidayType(), value.isTariffed(), value.getStartTime(),
                value.getEndTime(), value.getNormativeReference(), value.getValidFrom(), value.getValidTo(),
                value.isActive(), value.getCreatedAt(), value.getUpdatedAt());
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
