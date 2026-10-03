package ec.gob.simertpi.application.parking.rules;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ec.gob.simertpi.application.parking.calendar.ParkingCalendarService.ScheduleWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

public record ParkingRulesResult(UUID zoneId, UUID spaceId, ZonedDateTime evaluatedAt,
        boolean chargeable, boolean operational, boolean holiday, ScheduleWindow applicableSchedule,
        String applicableTariff, Integer minimumFractionMinutes, Integer maximumContinuousMinutes,
        Integer gracePeriodMinutes, String currency, BigDecimal unitPrice, Integer unitDurationMinutes,
        BigDecimal calculatedAmount, Integer requestedDurationMinutes, Long billedDurationMinutes,
        Instant expiresAt, boolean extensionAllowed, String reasonCode, @JsonIgnore UUID tariffId) { }
