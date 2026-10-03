package ec.gob.simertpi.application.parking.calendar;

import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.repository.HolidayRepository;
import ec.gob.simertpi.domain.parking.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParkingCalendarServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private HolidayRepository holidayRepository;

    private ParkingCalendarService service;

    private UUID zoneId;

    @BeforeEach
    void setUp() {
        service = new ParkingCalendarService(
                scheduleRepository,
                holidayRepository
        );

        zoneId = UUID.randomUUID();
    }

    @Test
    void shouldReturnTrueWhenTimeIsInsideWeeklySchedule() {

        Schedule schedule = schedule(
                (short) 5,
                LocalTime.of(8, 0),
                LocalTime.of(18, 0)
        );

        when(holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                LocalDate.of(2026, 10, 2),
                zoneId
        )).thenReturn(List.of());


        when(scheduleRepository.findByZoneIdAndDayOfWeekAndActiveTrue(
                zoneId,
                (short) 5
        )).thenReturn(List.of(schedule));

        assertTrue(
                service.isOperational(
                        zoneId,
                        LocalDate.of(2026, 10, 2),
                        LocalTime.of(10, 0)
                )
        );
    }

    @Test
    void shouldReturnFalseWhenTimeIsOutsideWeeklySchedule() {

        Schedule schedule = schedule(
                (short) 5,
                LocalTime.of(8, 0),
                LocalTime.of(18, 0)
        );

        LocalDate date = LocalDate.of(2026, 10, 2);

        when(holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                date,
                zoneId
        )).thenReturn(List.of());


        when(scheduleRepository.findByZoneIdAndDayOfWeekAndActiveTrue(
                zoneId,
                (short) 5
        )).thenReturn(List.of(schedule));

        assertFalse(
                service.isOperational(
                        zoneId,
                        date,
                        LocalTime.of(19, 0)
                )
        );
    }

    @Test
    void shouldReturnFalseWhenGlobalHolidayIsNonTariffed() {

        LocalDate date = LocalDate.of(2026, 12, 25);

        Holiday holiday = holiday(
                null,
                "NON_TARIFFED",
                false,
                null,
                null,
                LocalDate.of(2026, 1, 1),
                null
        );

        when(holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                date,
                zoneId
        )).thenReturn(List.of());

        when(holidayRepository.findByHolidayDateAndZoneIdIsNullAndActiveTrue(
                date
        )).thenReturn(List.of(holiday));

        assertFalse(
                service.isOperational(
                        zoneId,
                        date,
                        LocalTime.of(10, 0)
                )
        );

        verify(scheduleRepository, never())
                .findByZoneIdAndDayOfWeekAndActiveTrue(any(), anyShort());
    }

    @Test
    void shouldUseSpecialHolidaySchedule() {

        LocalDate date = LocalDate.of(2026, 11, 8);

        Holiday holiday = holiday(
                null,
                "SPECIAL_SCHEDULE",
                true,
                LocalTime.of(8, 0),
                LocalTime.of(13, 0),
                LocalDate.of(2026, 1, 1),
                null
        );

        when(holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                date,
                zoneId
        )).thenReturn(List.of());

        when(holidayRepository.findByHolidayDateAndZoneIdIsNullAndActiveTrue(
                date
        )).thenReturn(List.of(holiday));

        assertTrue(
                service.isOperational(
                        zoneId,
                        date,
                        LocalTime.of(10, 0)
                )
        );

        assertFalse(
                service.isOperational(
                        zoneId,
                        date,
                        LocalTime.of(14, 0)
                )
        );

        verify(scheduleRepository, never())
                .findByZoneIdAndDayOfWeekAndActiveTrue(any(), anyShort());
    }

    @Test
    void shouldGivePriorityToZoneSpecificHoliday() {

        LocalDate date = LocalDate.of(2026, 11, 8);
        Holiday zoneHoliday = holiday(
                zoneId,
                "SPECIAL_SCHEDULE",
                true,
                LocalTime.of(8, 0),
                LocalTime.of(13, 0),
                LocalDate.of(2026, 1, 1),
                null
        );

        when(holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                date,
                zoneId
        )).thenReturn(List.of(zoneHoliday));


        assertTrue(
                service.isOperational(
                        zoneId,
                        date,
                        LocalTime.of(10, 0)
                )
        );

        verify(holidayRepository, never())
                .findByHolidayDateAndZoneIdIsNullAndActiveTrue(date);
    }

    @Test
    void shouldIgnoreHolidayOutsideValidityPeriod() {

        LocalDate date = LocalDate.of(2026, 11, 8);

        Holiday holiday = holiday(
                null,
                "NON_TARIFFED",
                false,
                null,
                null,
                LocalDate.of(2027, 1, 1),
                null
        );

        Schedule schedule = schedule(
                (short) 7,
                LocalTime.of(8, 0),
                LocalTime.of(13, 0)
        );

        when(holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                date,
                zoneId
        )).thenReturn(List.of());

        when(holidayRepository.findByHolidayDateAndZoneIdIsNullAndActiveTrue(
                date
        )).thenReturn(List.of(holiday));

        when(scheduleRepository.findByZoneIdAndDayOfWeekAndActiveTrue(
                zoneId,
                (short) 7
        )).thenReturn(List.of(schedule));

        assertTrue(
                service.isOperational(
                        zoneId,
                        date,
                        LocalTime.of(10, 0)
                )
        );
    }

    private Schedule schedule(
            short dayOfWeek,
            LocalTime start,
            LocalTime end
    ) {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setZoneId(zoneId);
        schedule.setDayOfWeek(dayOfWeek);
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setActive(true);
        return schedule;
    }

    private Holiday holiday(
            UUID holidayZoneId,
            String holidayType,
            boolean tariffed,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        Holiday holiday = new Holiday();
        holiday.setId(UUID.randomUUID());
        holiday.setHolidayDate(LocalDate.of(2026, 11, 8));
        holiday.setName("ExcepciÃ³n de prueba");
        holiday.setZoneId(holidayZoneId);
        holiday.setHolidayType(holidayType);
        holiday.setTariffed(tariffed);
        holiday.setStartTime(startTime);
        holiday.setEndTime(endTime);
        holiday.setValidFrom(validFrom);
        holiday.setValidTo(validTo);
        holiday.setActive(true);
        return holiday;
    }
}
