package ec.gob.simertpi.application.parking.calendar;

import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.repository.HolidayRepository;
import ec.gob.simertpi.domain.parking.repository.ScheduleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ParkingCalendarService {

    private static final String HOLIDAY_NON_TARIFFED = "NON_TARIFFED";

    private final ScheduleRepository scheduleRepository;
    private final HolidayRepository holidayRepository;

    public ParkingCalendarService(
            ScheduleRepository scheduleRepository,
            HolidayRepository holidayRepository
    ) {
        this.scheduleRepository = scheduleRepository;
        this.holidayRepository = holidayRepository;
    }

    public boolean isOperational(
            UUID zoneId,
            LocalDate date,
            LocalTime time
    ) {

        if (zoneId == null) {
            throw new IllegalArgumentException("zoneId is required");
        }

        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }

        if (time == null) {
            throw new IllegalArgumentException("time is required");
        }

        Holiday applicableHoliday = resolveHoliday(zoneId, date);

        if (applicableHoliday != null) {

            if (!isValidOnDate(applicableHoliday, date)) {
                applicableHoliday = null;
            } else if (HOLIDAY_NON_TARIFFED.equals(applicableHoliday.getHolidayType())
                    || !applicableHoliday.isTariffed()) {
                return false;
            } else if (applicableHoliday.getStartTime() != null
                    && applicableHoliday.getEndTime() != null) {

                return !time.isBefore(applicableHoliday.getStartTime())
                        && time.isBefore(applicableHoliday.getEndTime());
            }
        }

        short dayOfWeek = (short) date.getDayOfWeek().getValue();

        List<Schedule> schedules =
                scheduleRepository.findByZoneIdAndDayOfWeekAndActiveTrue(
                        zoneId,
                        dayOfWeek
                );

        return schedules.stream()
                .anyMatch(schedule -> isWithinSchedule(schedule, time));
    }

    private Holiday resolveHoliday(
            UUID zoneId,
            LocalDate date
    ) {

        List<Holiday> zoneHolidays =
                holidayRepository.findByHolidayDateAndZoneIdAndActiveTrue(
                        date,
                        zoneId
                );

        Holiday zoneHoliday = zoneHolidays.stream()
                .filter(holiday -> isValidOnDate(holiday, date))
                .max(Comparator.comparing(Holiday::getValidFrom))
                .orElse(null);

        if (zoneHoliday != null) {
            return zoneHoliday;
        }

        List<Holiday> globalHolidays =
                holidayRepository.findByHolidayDateAndZoneIdIsNullAndActiveTrue(
                        date
                );

        return globalHolidays.stream()
                .filter(holiday -> isValidOnDate(holiday, date))
                .max(Comparator.comparing(Holiday::getValidFrom))
                .orElse(null);
    }

    private boolean isValidOnDate(
            Holiday holiday,
            LocalDate date
    ) {

        if (date.isBefore(holiday.getValidFrom())) {
            return false;
        }

        return holiday.getValidTo() == null
                || !date.isAfter(holiday.getValidTo());
    }

    private boolean isWithinSchedule(
            Schedule schedule,
            LocalTime time
    ) {

        return !time.isBefore(schedule.getStartTime())
                && time.isBefore(schedule.getEndTime());
    }
}
