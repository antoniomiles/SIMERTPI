package ec.gob.simertpi.application.parking.calendar;

import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.repository.HolidayRepository;
import ec.gob.simertpi.domain.parking.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class ParkingCalendarService {
    private final ScheduleRepository schedules;
    private final HolidayRepository holidays;
    public ParkingCalendarService(ScheduleRepository schedules, HolidayRepository holidays) {
        this.schedules = schedules; this.holidays = holidays;
    }
    public boolean isOperational(UUID zoneId, LocalDate date, LocalTime time) {
        return resolve(zoneId, date, time).operational();
    }
    public CalendarRules resolve(UUID zoneId, LocalDate date, LocalTime time) {
        if (zoneId == null || date == null || time == null) throw new IllegalArgumentException("zoneId, date and time are required");
        Holiday holiday = applicableHoliday(holidays.findByHolidayDateAndZoneIdAndActiveTrue(date, zoneId), date);
        if (holiday == null) holiday = applicableHoliday(holidays.findByHolidayDateAndZoneIdIsNullAndActiveTrue(date), date);
        boolean chargeable = holiday == null || (holiday.isTariffed() && !"NON_TARIFFED".equals(holiday.getHolidayType()));
        if (holiday != null && holiday.getStartTime() != null && holiday.getEndTime() != null) {
            var window = new ScheduleWindow(holiday.getStartTime(), holiday.getEndTime(), "EXCEPTION");
            boolean open = contains(window, time);
            return new CalendarRules(open, true, chargeable, window,
                    !open ? "OUTSIDE_OPERATION_HOURS" : chargeable ? "RULES_RESOLVED" : "FREE_PARKING");
        }
        short day = (short) date.getDayOfWeek().getValue();
        List<Schedule> applicable = validSchedules(schedules.findByZoneIdAndDayOfWeekAndActiveTrue(zoneId, day), date);
        String source = "ZONE";
        if (applicable.isEmpty()) {
            applicable = validSchedules(schedules.findByZoneIdIsNullAndDayOfWeekAndActiveTrue(day), date);
            source = "GENERAL";
        }
        if (applicable.isEmpty()) return new CalendarRules(false, holiday != null, chargeable, null, "NO_ACTIVE_SCHEDULE");
        // Multiple disjoint windows are supported; a zone calendar suppresses the general one.
        Schedule selected = applicable.stream().filter(s -> !time.isBefore(s.getStartTime()) && time.isBefore(s.getEndTime()))
                .findFirst().orElse(applicable.getFirst());
        var window = new ScheduleWindow(selected.getStartTime(), selected.getEndTime(), source);
        boolean open = contains(window, time);
        return new CalendarRules(open, holiday != null, chargeable, window,
                !open ? "OUTSIDE_OPERATION_HOURS" : chargeable ? "RULES_RESOLVED" : "FREE_PARKING");
    }
    private List<Schedule> validSchedules(List<Schedule> rows, LocalDate date) {
        return rows.stream().filter(s -> s.isActive() && (s.getValidFrom() == null || !date.isBefore(s.getValidFrom()))
                && (s.getValidTo() == null || !date.isAfter(s.getValidTo())))
                .sorted(Comparator.comparing(Schedule::getStartTime).thenComparing(s -> String.valueOf(s.getId()))).toList();
    }
    private Holiday applicableHoliday(List<Holiday> rows, LocalDate date) {
        return rows.stream().filter(h -> h.isActive() && h.getValidFrom() != null && !date.isBefore(h.getValidFrom())
                && (h.getValidTo() == null || !date.isAfter(h.getValidTo())))
                .max(Comparator.comparing(Holiday::getValidFrom)).orElse(null);
    }
    private boolean contains(ScheduleWindow window, LocalTime time) {
        return !time.isBefore(window.startTime()) && time.isBefore(window.endTime());
    }
    public record ScheduleWindow(LocalTime startTime, LocalTime endTime, String source) { }
    public record CalendarRules(boolean operational, boolean holiday, boolean chargeable,
                                ScheduleWindow schedule, String reasonCode) { }
}
