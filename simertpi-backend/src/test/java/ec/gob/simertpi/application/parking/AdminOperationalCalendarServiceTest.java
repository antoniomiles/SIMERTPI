package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.parking.admin.dto.CalendarExceptionWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleEndRequest;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.repository.HolidayRepository;
import ec.gob.simertpi.domain.parking.repository.ScheduleRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminOperationalCalendarServiceTest {
    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final HolidayRepository holidays = mock(HolidayRepository.class);
    private final ZoneRepository zones = mock(ZoneRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private AdminOperationalCalendarService service;

    @BeforeEach
    void setUp() {
        service = new AdminOperationalCalendarService(schedules, holidays, zones, audit, jdbc);
        when(schedules.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(holidays.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsScheduleWithoutInventingValuesAndAuditsReasonAndFields() {
        UUID zoneId = UUID.randomUUID();
        when(zones.existsById(zoneId)).thenReturn(true);
        when(schedules.findAll()).thenReturn(List.of());
        LocalDate tomorrow = today().plusDays(1);
        var request = new OperationalScheduleWriteRequest(zoneId, (short) 1, LocalTime.of(8, 0),
                LocalTime.of(17, 0), tomorrow, null, "Ordenanza registrada", null);

        Schedule result = service.createSchedule(request);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getZoneId()).isEqualTo(zoneId);
        assertThat(result.getValidFrom()).isEqualTo(tomorrow);
        assertThat(result.isActive()).isTrue();
        verify(audit).recordOutcome(eq("OPERATIONAL_SCHEDULE_CREATED"), eq("PARKING_SCHEDULE"),
                eq(result.getId()), eq("SUCCESS"), argThat(metadata ->
                        "Ordenanza registrada".equals(metadata.get("reason"))
                                && LocalTime.of(8, 0).equals(metadata.get("after.startTime"))));
    }

    @Test
    void rejectsScheduleStartingTodayToAvoidChangingAlreadyElapsedCalendarHistory() {
        var request = new OperationalScheduleWriteRequest(null, (short) 1, LocalTime.of(8, 0),
                LocalTime.of(17, 0), today(), null, "Configuración autorizada", null);

        assertThatThrownBy(() -> service.createSchedule(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("desde mañana");
        verify(schedules, never()).saveAndFlush(any());
    }

    @Test
    void rejectsOverlappingPeriodsForSameScopeAndAllowsDisjointTimeIntervals() {
        LocalDate tomorrow = today().plusDays(1);
        Schedule existing = schedule(null, (short) 1, LocalTime.of(8, 0), LocalTime.of(12, 0), tomorrow, null);
        when(zones.existsById(any())).thenReturn(true);
        when(schedules.findAll()).thenReturn(List.of(existing));
        var overlap = new OperationalScheduleWriteRequest(null, (short) 1, LocalTime.of(11, 0),
                LocalTime.of(13, 0), tomorrow, null, "Motivo", null);
        assertThatThrownBy(() -> service.createSchedule(overlap)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("superpone");

        var disjoint = new OperationalScheduleWriteRequest(null, (short) 1, LocalTime.of(12, 0),
                LocalTime.of(13, 0), tomorrow, null, "Motivo", null);
        Schedule accepted = service.createSchedule(disjoint);
        assertThat(accepted.getStartTime()).isEqualTo(LocalTime.NOON);
    }

    @Test
    void revisionClosesPriorScheduleAndCreatesSuccessorAtomicallyWithAudit() {
        LocalDate today = today();
        OffsetDateTime updatedAt = OffsetDateTime.now(ZoneId.of("America/Guayaquil")).minusDays(1);
        Schedule current = schedule(null, (short) 1, LocalTime.of(8, 0), LocalTime.of(17, 0), today.minusDays(50), null);
        current.setUpdatedAt(updatedAt);
        when(schedules.findByIdForUpdate(current.getId())).thenReturn(Optional.of(current));
        when(zones.existsById(any())).thenReturn(true);
        when(schedules.findAll()).thenAnswer(invocation -> List.of(current));
        LocalDate effectiveFrom = today.plusDays(2);
        var request = new OperationalScheduleWriteRequest(null, (short) 1, LocalTime.of(9, 0),
                LocalTime.of(18, 0), effectiveFrom, null, "Ajuste autorizado", updatedAt);

        Schedule successor = service.reviseCurrentSchedule(current.getId(), request);

        assertThat(current.getValidTo()).isEqualTo(effectiveFrom.minusDays(1));
        assertThat(successor.getId()).isNotEqualTo(current.getId());
        assertThat(successor.getValidFrom()).isEqualTo(effectiveFrom);
        assertThat(successor.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        verify(schedules, times(2)).saveAndFlush(any());
        verify(audit).recordOutcome(eq("OPERATIONAL_SCHEDULE_REVISED"), eq("PARKING_SCHEDULE"),
                eq(successor.getId()), eq("SUCCESS"), argThat(metadata ->
                        current.getId().equals(metadata.get("previousScheduleId"))
                                && effectiveFrom.minusDays(1).equals(metadata.get("previousVersionEnd"))));
    }

    @Test
    void rejectsChangingZoneScopeWhenRevisingAnEffectiveSchedule() {
        LocalDate today = today();
        UUID oldZone = UUID.randomUUID();
        UUID newZone = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.now(ZoneId.of("America/Guayaquil")).minusDays(1);
        Schedule current = schedule(oldZone, (short) 1, LocalTime.of(8, 0), LocalTime.of(17, 0),
                today.minusDays(50), null);
        current.setUpdatedAt(updatedAt);
        when(schedules.findByIdForUpdate(current.getId())).thenReturn(Optional.of(current));
        when(zones.existsById(newZone)).thenReturn(true);
        var request = new OperationalScheduleWriteRequest(newZone, (short) 1, LocalTime.of(9, 0),
                LocalTime.of(18, 0), today.plusDays(2), null, "Cambio solicitado", updatedAt);

        assertThatThrownBy(() -> service.reviseCurrentSchedule(current.getId(), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("mismo");
        assertThat(current.getValidTo()).isNull();
        verify(schedules, never()).saveAndFlush(any());
    }

    @Test
    void rejectsStaleUpdateAndDoesNotPersist() {
        Schedule current = schedule(null, (short) 1, LocalTime.of(8, 0), LocalTime.of(17, 0), today(), null);
        OffsetDateTime latest = OffsetDateTime.now(ZoneId.of("America/Guayaquil"));
        current.setUpdatedAt(latest);
        when(schedules.findByIdForUpdate(current.getId())).thenReturn(Optional.of(current));
        var request = new OperationalScheduleWriteRequest(null, (short) 1, LocalTime.of(9, 0),
                LocalTime.of(18, 0), today().plusDays(1), null, "Motivo", latest.minusSeconds(1));

        assertThatThrownBy(() -> service.updateSchedule(current.getId(), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Otro administrador");
        verify(schedules, never()).saveAndFlush(any());
    }

    @Test
    void endSchedulesCurrentHoursFromTomorrowWithoutChangingPastStartOrRules() {
        Schedule current = schedule(null, (short) 1, LocalTime.of(8, 0), LocalTime.of(17, 0), today().minusDays(30), null);
        OffsetDateTime updatedAt = OffsetDateTime.now(ZoneId.of("America/Guayaquil")).minusDays(1);
        current.setUpdatedAt(updatedAt);
        when(schedules.findByIdForUpdate(current.getId())).thenReturn(Optional.of(current));
        LocalDate effectiveFrom = today().plusDays(1);

        Schedule ended = service.endCurrentSchedule(current.getId(),
                new OperationalScheduleEndRequest(effectiveFrom, "Cierre programado", updatedAt));

        assertThat(ended.getValidFrom()).isEqualTo(today().minusDays(30));
        assertThat(ended.getValidTo()).isEqualTo(today());
        assertThat(ended.isActive()).isTrue(); // keeps the historical interval resolvable
        verify(audit).recordOutcome(eq("OPERATIONAL_SCHEDULE_END_SCHEDULED"), eq("PARKING_SCHEDULE"),
                eq(current.getId()), eq("SUCCESS"), anyMap());
    }

    @Test
    void rejectsHolidayDuplicateScopeAndKeepsExplicitNoChargeChoice() {
        Holiday duplicate = new Holiday();
        duplicate.setId(UUID.randomUUID());
        duplicate.setHolidayDate(today().plusDays(30));
        duplicate.setZoneId(null);
        when(holidays.findAll()).thenReturn(List.of(duplicate));
        var request = holidayRequest(null, false);
        assertThatThrownBy(() -> service.createHoliday(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ya existe una excepción");

        when(holidays.findAll()).thenReturn(List.of());
        Holiday created = service.createHoliday(request);
        assertThat(created.isTariffed()).isFalse();
        assertThat(created.getHolidayType()).isEqualTo("NON_TARIFFED");
        verify(audit).recordOutcome(eq("CALENDAR_EXCEPTION_CREATED"), eq("CALENDAR_EXCEPTION"),
                eq(created.getId()), eq("SUCCESS"), anyMap());
    }

    @Test
    void rejectsEditingCurrentOrPastCalendarConfiguration() {
        Holiday current = new Holiday();
        current.setId(UUID.randomUUID());
        current.setHolidayDate(today());
        current.setValidFrom(today());
        current.setName("Feriado existente");
        current.setHolidayType("NATIONAL_HOLIDAY");
        current.setCreatedAt(OffsetDateTime.now(ZoneId.of("America/Guayaquil")));
        current.setUpdatedAt(current.getCreatedAt());
        current.setTariffed(false);
        current.setActive(true);
        when(holidays.findByIdForUpdate(current.getId())).thenReturn(Optional.of(current));
        var request = new CalendarExceptionWriteRequest(today(), "Otro nombre", null, "NON_TARIFFED", false,
                null, null, null, today(), null, "Corrección", current.getUpdatedAt());

        assertThatThrownBy(() -> service.updateHoliday(current.getId(), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("fecha actual");
        verify(holidays, never()).saveAndFlush(any());
    }

    @Test
    void rejectsHolidayWhoseDateIsTodayEvenWhenItWouldBeCreatedDuringTheDay() {
        LocalDate today = today();
        var request = new CalendarExceptionWriteRequest(today, "Excepción de hoy", null,
                "NATIONAL_HOLIDAY", true, null, null, null, today, null,
                "Configuración autorizada", null);

        assertThatThrownBy(() -> service.createHoliday(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("desde mañana");
        verify(holidays, never()).saveAndFlush(any());
    }

    @Test
    void rejectsFutureHolidayWithConfigurationEffectiveToday() {
        LocalDate today = today();
        var request = new CalendarExceptionWriteRequest(today.plusDays(1), "Excepción futura", null,
                "NATIONAL_HOLIDAY", true, null, null, null, today, null,
                "Configuración autorizada", null);

        assertThatThrownBy(() -> service.createHoliday(request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("desde mañana");
        verify(holidays, never()).saveAndFlush(any());
    }

    private static Schedule schedule(UUID zoneId, short day, LocalTime start, LocalTime end,
            LocalDate validFrom, LocalDate validTo) {
        Schedule row = new Schedule();
        row.setId(UUID.randomUUID()); row.setZoneId(zoneId); row.setDayOfWeek(day);
        row.setStartTime(start); row.setEndTime(end); row.setValidFrom(validFrom); row.setValidTo(validTo);
        row.setCreatedAt(OffsetDateTime.now(ZoneId.of("America/Guayaquil")));
        row.setUpdatedAt(row.getCreatedAt()); row.setActive(true);
        return row;
    }

    private static CalendarExceptionWriteRequest holidayRequest(UUID zoneId, boolean tariffed) {
        LocalDate date = today().plusDays(30);
        return new CalendarExceptionWriteRequest(date, tariffed ? "Fecha especial" : "Día sin cobro", zoneId,
                tariffed ? "NATIONAL_HOLIDAY" : "NON_TARIFFED", tariffed, null, null, null,
                today().plusDays(1), date.plusDays(10), "Configuración explícita", null);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Guayaquil"));
    }
}
