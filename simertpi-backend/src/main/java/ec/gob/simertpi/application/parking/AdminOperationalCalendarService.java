package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.parking.admin.dto.CalendarExceptionWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleEndRequest;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.repository.HolidayRepository;
import ec.gob.simertpi.domain.parking.repository.ScheduleRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@Service
public class AdminOperationalCalendarService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guayaquil");
    private static final Set<String> HOLIDAY_TYPES = Set.of(
            "NATIONAL_HOLIDAY", "NON_TARIFFED", "SPECIAL_SCHEDULE", "MUNICIPAL_EXCEPTION");
    private static final long LOCK_NAMESPACE = 0x53494D45L;
    private static final long CALENDAR_LOCK_KEY = 0x43414C31L;

    private final ScheduleRepository schedules;
    private final HolidayRepository holidays;
    private final ZoneRepository zones;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public AdminOperationalCalendarService(ScheduleRepository schedules, HolidayRepository holidays,
            ZoneRepository zones, AuditService audit, JdbcTemplate jdbc) {
        this.schedules = schedules;
        this.holidays = holidays;
        this.zones = zones;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional
    public Schedule createSchedule(OperationalScheduleWriteRequest request) {
        lockCalendarWrites();
        validateSchedule(request);
        if (request.validFrom() == null || !request.validFrom().isAfter(today())
                || request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "Un horario nuevo debe iniciar desde mañana o una fecha futura de Ecuador; no se permiten altas para hoy o fechas históricas.");
        }
        Schedule target = new Schedule();
        target.setId(UUID.randomUUID());
        target.setCreatedAt(now());
        target.setActive(true);
        apply(target, request);
        validateScheduleOverlap(target, null);
        Schedule saved = schedules.saveAndFlush(target);
        audit.recordOutcome("OPERATIONAL_SCHEDULE_CREATED", "PARKING_SCHEDULE", saved.getId(), "SUCCESS",
                scheduleAudit(null, saved, request.reason()));
        return saved;
    }

    @Transactional
    public Schedule updateSchedule(UUID id, OperationalScheduleWriteRequest request) {
        lockCalendarWrites();
        validateSchedule(request);
        Schedule target = schedules.findByIdForUpdate(id).orElseThrow(() -> notFound("No existe el horario indicado."));
        checkScheduleVersion(target, request.expectedUpdatedAt());
        if (target.isActive() && target.getValidFrom() != null
                && !target.getValidFrom().isAfter(today())) {
            throw conflict("El horario ya forma parte de la configuración vigente. Para conservar el historial, crea el cambio en un nuevo período futuro.");
        }
        if (target.isActive() && (request.validFrom() == null || !request.validFrom().isAfter(today()))) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "La edición de una programación debe iniciar desde mañana o una fecha posterior de Ecuador.");
        }
        Map<String, Object> before = scheduleValues(target);
        apply(target, request);
        validateScheduleOverlap(target, id);
        target.setUpdatedAt(now());
        Schedule saved = schedules.saveAndFlush(target);
        audit.recordOutcome("OPERATIONAL_SCHEDULE_UPDATED", "PARKING_SCHEDULE", id, "SUCCESS",
                scheduleAudit(before, saved, request.reason()));
        return saved;
    }

    @Transactional
    public Schedule reviseCurrentSchedule(UUID id, OperationalScheduleWriteRequest request) {
        lockCalendarWrites();
        validateSchedule(request);
        Schedule previous = schedules.findByIdForUpdate(id)
                .orElseThrow(() -> notFound("No existe el horario indicado."));
        checkScheduleVersion(previous, request.expectedUpdatedAt());
        LocalDate effectiveFrom = request.validFrom();
        if (!previous.isActive() || previous.getValidFrom() != null && previous.getValidFrom().isAfter(today())
                || previous.getValidTo() != null && previous.getValidTo().isBefore(today())) {
            throw conflict("Solo se puede crear una versión desde un horario actualmente vigente.");
        }
        if (effectiveFrom == null || !effectiveFrom.isAfter(today())) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "La nueva versión debe iniciar en una fecha futura de Ecuador.");
        }
        if (!java.util.Objects.equals(previous.getZoneId(), request.zoneId())) {
            throw conflict("Una nueva versiÃ³n debe conservar el mismo Ã¡mbito de zona. Los cambios de Ã¡mbito deben registrarse como programaciones futuras independientes.");
        }
        Map<String, Object> before = scheduleValues(previous);
        LocalDate previousEnd = effectiveFrom.minusDays(1);
        if (previous.getValidTo() == null || previous.getValidTo().isAfter(previousEnd)) {
            previous.setValidTo(previousEnd);
        }
        previous.setUpdatedAt(now());
        schedules.saveAndFlush(previous);

        Schedule successor = new Schedule();
        successor.setId(UUID.randomUUID());
        successor.setCreatedAt(now());
        successor.setActive(true);
        apply(successor, request);
        validateScheduleOverlap(successor, null);
        Schedule saved = schedules.saveAndFlush(successor);
        Map<String, Object> metadata = scheduleAudit(before, saved, request.reason());
        metadata.put("previousScheduleId", previous.getId());
        metadata.put("previousVersionEnd", previous.getValidTo());
        audit.recordOutcome("OPERATIONAL_SCHEDULE_REVISED", "PARKING_SCHEDULE", saved.getId(), "SUCCESS", metadata);
        return saved;
    }

    @Transactional
    public Schedule endCurrentSchedule(UUID id, OperationalScheduleEndRequest request) {
        lockCalendarWrites();
        Schedule target = schedules.findByIdForUpdate(id)
                .orElseThrow(() -> notFound("No existe el horario indicado."));
        checkScheduleVersion(target, request.expectedUpdatedAt());
        LocalDate current = today();
        if (!target.isActive() || target.getValidFrom() != null && target.getValidFrom().isAfter(current)
                || target.getValidTo() != null && target.getValidTo().isBefore(current)) {
            throw conflict("Solo se puede programar el cierre de un horario actualmente vigente.");
        }
        if (!request.effectiveFrom().isAfter(current)) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "El cierre debe iniciar desde mañana como mínimo, según la fecha de Ecuador.");
        }
        LocalDate endDate = request.effectiveFrom().minusDays(1);
        if (target.getValidTo() != null && target.getValidTo().isBefore(endDate)) {
            throw conflict("El horario ya tiene una fecha de finalización anterior a la solicitada.");
        }
        Map<String, Object> before = scheduleValues(target);
        target.setValidTo(endDate);
        target.setUpdatedAt(now());
        Schedule saved = schedules.saveAndFlush(target);
        Map<String, Object> metadata = scheduleAudit(before, saved, request.reason());
        metadata.put("effectiveFrom", request.effectiveFrom());
        audit.recordOutcome("OPERATIONAL_SCHEDULE_END_SCHEDULED", "PARKING_SCHEDULE", id, "SUCCESS", metadata);
        return saved;
    }

    @Transactional
    public Schedule deactivateSchedule(UUID id, OffsetDateTime expectedUpdatedAt, String reason) {
        lockCalendarWrites();
        Schedule target = schedules.findByIdForUpdate(id).orElseThrow(() -> notFound("No existe el horario indicado."));
        checkScheduleVersion(target, expectedUpdatedAt);
        if (!target.isActive()) throw conflict("El horario ya está desactivado.");
        if (target.getValidFrom() == null || !target.getValidFrom().isAfter(today())) {
            throw conflict("Solo se puede cancelar una programación futura. Para cambios vigentes se requiere definir un cierre con fecha y motivo.");
        }
        Map<String, Object> before = scheduleValues(target);
        target.setActive(false);
        target.setUpdatedAt(now());
        Schedule saved = schedules.saveAndFlush(target);
        audit.recordOutcome("OPERATIONAL_SCHEDULE_DEACTIVATED", "PARKING_SCHEDULE", id, "SUCCESS",
                scheduleAudit(before, saved, reason));
        return saved;
    }

    @Transactional
    public Holiday createHoliday(CalendarExceptionWriteRequest request) {
        lockCalendarWrites();
        validateHoliday(request, null);
        if (!request.holidayDate().isAfter(today()) || !request.validFrom().isAfter(today())) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "La fecha especial y su vigencia deben iniciar desde mañana o en fechas posteriores de Ecuador.");
        }
        Holiday target = new Holiday();
        target.setId(UUID.randomUUID());
        target.setCreatedAt(now());
        target.setActive(true);
        apply(target, request);
        Holiday saved = holidays.saveAndFlush(target);
        audit.recordOutcome("CALENDAR_EXCEPTION_CREATED", "CALENDAR_EXCEPTION", saved.getId(), "SUCCESS",
                holidayAudit(null, saved, request.reason()));
        return saved;
    }

    @Transactional
    public Holiday updateHoliday(UUID id, CalendarExceptionWriteRequest request) {
        lockCalendarWrites();
        validateHoliday(request, id);
        Holiday target = holidays.findByIdForUpdate(id).orElseThrow(() -> notFound("No existe la excepción indicada."));
        checkHolidayVersion(target, request.expectedUpdatedAt());
        if (target.isActive() && (target.getHolidayDate() == null || !target.getHolidayDate().isAfter(today())
                || !target.getValidFrom().isAfter(today()))) {
            throw conflict("La excepción ya está vigente o corresponde a una fecha actual o histórica. Solo pueden editarse excepciones cuya fecha y vigencia sean futuras.");
        }
        if (target.isActive() && (!request.holidayDate().isAfter(today()) || !request.validFrom().isAfter(today()))) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "Una excepción programada debe mantener su fecha y vigencia desde mañana o en fechas posteriores.");
        }
        Map<String, Object> before = holidayValues(target);
        apply(target, request);
        target.setUpdatedAt(now());
        Holiday saved = holidays.saveAndFlush(target);
        audit.recordOutcome("CALENDAR_EXCEPTION_UPDATED", "CALENDAR_EXCEPTION", id, "SUCCESS",
                holidayAudit(before, saved, request.reason()));
        return saved;
    }

    @Transactional
    public Holiday deactivateHoliday(UUID id, OffsetDateTime expectedUpdatedAt, String reason) {
        lockCalendarWrites();
        Holiday target = holidays.findByIdForUpdate(id).orElseThrow(() -> notFound("No existe la excepción indicada."));
        checkHolidayVersion(target, expectedUpdatedAt);
        if (!target.isActive()) throw conflict("La excepción ya está desactivada.");
        if (!target.getHolidayDate().isAfter(today()) || !target.getValidFrom().isAfter(today())) {
            throw conflict("Solo se puede cancelar una excepción cuya fecha especial y vigencia sean futuras.");
        }
        Map<String, Object> before = holidayValues(target);
        target.setActive(false);
        target.setUpdatedAt(now());
        Holiday saved = holidays.saveAndFlush(target);
        audit.recordOutcome("CALENDAR_EXCEPTION_DEACTIVATED", "CALENDAR_EXCEPTION", id, "SUCCESS",
                holidayAudit(before, saved, reason));
        return saved;
    }

    private void validateSchedule(OperationalScheduleWriteRequest request) {
        if (request.startTime() == null || request.endTime() == null || !request.startTime().isBefore(request.endTime())) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "La hora inicial debe ser anterior a la hora final.");
        }
        validateRange(request.validFrom(), request.validTo());
        validateZone(request.zoneId());
    }

    private void validateHoliday(CalendarExceptionWriteRequest request, UUID excludedId) {
        validateZone(request.zoneId());
        if (!HOLIDAY_TYPES.contains(request.holidayType())) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "El tipo de excepción no está permitido.");
        }
        if ((request.startTime() == null) != (request.endTime() == null)
                || (request.startTime() != null && !request.startTime().isBefore(request.endTime()))) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "Configure ambas horas de la excepción y asegure que la hora inicial sea anterior a la final.");
        }
        validateRange(request.validFrom(), request.validTo());
        if (request.validFrom().isAfter(request.holidayDate())
                || (request.validTo() != null && request.validTo().isBefore(request.holidayDate()))) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "La fecha especial debe estar dentro del período de vigencia.");
        }
        if (request.holidayType().equals("NON_TARIFFED") && request.tariffed()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "El tipo NON_TARIFFED requiere que la opción de cobro esté desactivada.");
        }
        boolean duplicate = holidays.findAll().stream().anyMatch(existing ->
                !existing.getId().equals(excludedId)
                        && existing.getHolidayDate().equals(request.holidayDate())
                        && java.util.Objects.equals(existing.getZoneId(), request.zoneId()));
        if (duplicate) throw conflict("Ya existe una excepción para esa fecha y ámbito. Edita el registro existente para conservar la unicidad.");
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "La fecha final no puede ser anterior a la fecha inicial.");
        }
    }

    private void validateZone(UUID zoneId) {
        if (zoneId != null && !zones.existsById(zoneId)) throw notFound("La zona seleccionada no existe.");
    }

    private void validateScheduleOverlap(Schedule candidate, UUID excludedId) {
        boolean overlaps = schedules.findAll().stream()
                .filter(Schedule::isActive)
                .filter(row -> !row.getId().equals(excludedId))
                .filter(row -> java.util.Objects.equals(row.getZoneId(), candidate.getZoneId()))
                .filter(row -> row.getDayOfWeek().equals(candidate.getDayOfWeek()))
                .anyMatch(row -> dateRangesOverlap(row.getValidFrom(), row.getValidTo(), candidate.getValidFrom(), candidate.getValidTo())
                        && row.getStartTime().isBefore(candidate.getEndTime())
                        && candidate.getStartTime().isBefore(row.getEndTime()));
        if (overlaps) throw conflict("El horario se superpone con otro intervalo activo del mismo ámbito y día.");
    }

    private static boolean dateRangesOverlap(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        return (aTo == null || bFrom == null || !aTo.isBefore(bFrom))
                && (bTo == null || aFrom == null || !bTo.isBefore(aFrom));
    }

    private void checkScheduleVersion(Schedule target, OffsetDateTime expected) {
        if (expected == null || !target.getUpdatedAt().toInstant().equals(expected.toInstant())) {
            throw conflict("Otro administrador actualizó el horario. Actualiza la información antes de volver a guardar.");
        }
    }

    private void checkHolidayVersion(Holiday target, OffsetDateTime expected) {
        if (expected == null || !target.getUpdatedAt().toInstant().equals(expected.toInstant())) {
            throw conflict("Otro administrador actualizó la excepción. Actualiza la información antes de volver a guardar.");
        }
    }

    private void apply(Schedule target, OperationalScheduleWriteRequest request) {
        target.setZoneId(request.zoneId());
        target.setDayOfWeek(request.dayOfWeek());
        target.setStartTime(request.startTime());
        target.setEndTime(request.endTime());
        target.setValidFrom(request.validFrom());
        target.setValidTo(request.validTo());
        target.setUpdatedAt(now());
    }

    private void apply(Holiday target, CalendarExceptionWriteRequest request) {
        target.setHolidayDate(request.holidayDate());
        target.setName(request.name().trim());
        target.setZoneId(request.zoneId());
        target.setHolidayType(request.holidayType());
        target.setTariffed(request.tariffed());
        target.setStartTime(request.startTime());
        target.setEndTime(request.endTime());
        target.setNormativeReference(request.normativeReference() == null ? null : request.normativeReference().trim());
        target.setValidFrom(request.validFrom());
        target.setValidTo(request.validTo());
        target.setUpdatedAt(now());
    }

    private Map<String, Object> scheduleAudit(Map<String, Object> before, Schedule saved, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason.trim());
        if (before != null) before.forEach((key, value) -> metadata.put("before." + key, value));
        scheduleValues(saved).forEach((key, value) -> metadata.put("after." + key, value));
        return metadata;
    }

    private Map<String, Object> holidayAudit(Map<String, Object> before, Holiday saved, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason.trim());
        if (before != null) before.forEach((key, value) -> metadata.put("before." + key, value));
        holidayValues(saved).forEach((key, value) -> metadata.put("after." + key, value));
        return metadata;
    }

    private Map<String, Object> scheduleValues(Schedule row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("zoneId", row.getZoneId()); values.put("dayOfWeek", row.getDayOfWeek());
        values.put("startTime", row.getStartTime()); values.put("endTime", row.getEndTime());
        values.put("validFrom", row.getValidFrom()); values.put("validTo", row.getValidTo()); values.put("active", row.isActive());
        return values;
    }

    private Map<String, Object> holidayValues(Holiday row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("holidayDate", row.getHolidayDate()); values.put("name", row.getName()); values.put("zoneId", row.getZoneId());
        values.put("holidayType", row.getHolidayType()); values.put("tariffed", row.isTariffed());
        values.put("startTime", row.getStartTime()); values.put("endTime", row.getEndTime());
        values.put("normativeReference", row.getNormativeReference()); values.put("validFrom", row.getValidFrom());
        values.put("validTo", row.getValidTo()); values.put("active", row.isActive());
        return values;
    }

    private void lockCalendarWrites() {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + LOCK_NAMESPACE + ", " + CALENDAR_LOCK_KEY + ")");
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MICROS); }
    private static LocalDate today() { return LocalDate.now(BUSINESS_ZONE); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(CONFLICT, message); }
}
