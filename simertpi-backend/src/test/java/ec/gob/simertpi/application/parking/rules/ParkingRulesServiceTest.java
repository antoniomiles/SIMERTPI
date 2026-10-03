package ec.gob.simertpi.application.parking.rules;

import ec.gob.simertpi.application.parking.calendar.ParkingCalendarService;
import ec.gob.simertpi.domain.parking.entity.*;
import ec.gob.simertpi.domain.parking.repository.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ParkingRulesServiceTest {
    ScheduleRepository schedules;
    HolidayRepository holidays;
    TariffRepository tariffs;
    ParkingSpaceRepository spaces;
    StreetRepository streets;
    ZoneRepository zones;
    ParkingRulesService service;
    UUID zoneId, spaceId;
    Instant at = Instant.parse("2026-10-05T15:00:00Z");
    Tariff tariff;
    Schedule schedule;
    ParkingSession session;

    @BeforeEach void setup() {
        zoneId = UUID.randomUUID(); spaceId = UUID.randomUUID();
        schedules = mock(ScheduleRepository.class); holidays = mock(HolidayRepository.class);
        tariffs = mock(TariffRepository.class); spaces = mock(ParkingSpaceRepository.class);
        streets = mock(StreetRepository.class); zones = mock(ZoneRepository.class);
        service = new ParkingRulesService(new ParkingCalendarService(schedules, holidays), tariffs, spaces, streets, zones, "America/Guayaquil");
        schedule = new Schedule(); schedule.setId(UUID.randomUUID()); schedule.setZoneId(zoneId); schedule.setDayOfWeek((short)1);
        schedule.setStartTime(LocalTime.of(9,0)); schedule.setEndTime(LocalTime.of(18,0));
        when(schedules.findByZoneIdAndDayOfWeekAndActiveTrue(eq(zoneId), anyShort())).thenReturn(List.of(schedule));
        tariff = new Tariff(); tariff.setId(UUID.randomUUID()); tariff.setCode("TEST-ONLY"); tariff.setAmount(new BigDecimal("1.23"));
        tariff.setDurationMinutes(60); tariff.setMinMinutes(15); tariff.setMaxContinuousMinutes(180);
        tariff.setGracePeriodMinutes(7); tariff.setCurrency("USD"); tariff.setRoundingMode("HALF_UP");
        tariff.setValidFrom(at.minus(Duration.ofDays(1)) .atOffset(ZoneOffset.UTC));
        when(tariffs.findActiveTariffsAt(any())).thenReturn(List.of(tariff));
        when(tariffs.findById(tariff.getId())).thenReturn(Optional.of(tariff));
        Zone zone = new Zone(); zone.setId(zoneId);
        Street street = new Street(); street.setId(UUID.randomUUID()); street.setZoneId(zoneId);
        ParkingSpace space = new ParkingSpace(); space.setId(spaceId); space.setStreetId(street.getId());
        when(spaces.findById(spaceId)).thenReturn(Optional.of(space)); when(spaces.findByQrCode("QR-TEST")).thenReturn(Optional.of(space));
        when(streets.findById(street.getId())).thenReturn(Optional.of(street)); when(zones.findById(zoneId)).thenReturn(Optional.of(zone));
        session = new ParkingSession(); session.setParkingSpaceId(spaceId); session.setTariffId(tariff.getId());
        session.setStartedAt(at.minusSeconds(3600).atOffset(ZoneOffset.UTC)); session.setExpectedEndAt(at.plusSeconds(1800).atOffset(ZoneOffset.UTC));
    }
    ParkingRulesResult evaluate(Integer minutes) { return service.evaluate(spaceId, null, at, minutes); }
    Holiday holiday(boolean chargeable, LocalTime start, LocalTime end) {
        Holiday h = new Holiday(); h.setId(UUID.randomUUID()); h.setHolidayDate(at.atZone(ZoneId.of("America/Guayaquil")).toLocalDate());
        h.setHolidayType(chargeable ? "SPECIAL_SCHEDULE" : "NON_TARIFFED"); h.setTariffed(chargeable); h.setValidFrom(LocalDate.of(2026,1,1));
        h.setStartTime(start); h.setEndTime(end); return h;
    }
    @Test void currentScheduleAndTariffResolve() {
        var r = evaluate(60); assertThat(r.operational()).isTrue(); assertThat(r.chargeable()).isTrue();
        assertThat(r.reasonCode()).isEqualTo("RULES_RESOLVED"); assertThat(r.applicableTariff()).isEqualTo("TEST-ONLY");
        assertThat(r.expiresAt()).isEqualTo(at.plusSeconds(3600)); assertThat(r.evaluatedAt().getHour()).isEqualTo(10);
    }
    @Test void outsideHours() { assertThat(service.evaluate(spaceId,null,Instant.parse("2026-10-05T13:59:00Z"),60).reasonCode()).isEqualTo("OUTSIDE_OPERATION_HOURS"); }
    @Test void noSchedule() { when(schedules.findByZoneIdAndDayOfWeekAndActiveTrue(any(),any())).thenReturn(List.of()); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_ACTIVE_SCHEDULE"); }
    @Test void nonChargeableHolidayPrevails() {
        when(holidays.findByHolidayDateAndZoneIdIsNullAndActiveTrue(any())).thenReturn(List.of(holiday(false,null,null)));
        var r=evaluate(60); assertThat(r.holiday()).isTrue(); assertThat(r.chargeable()).isFalse(); assertThat(r.reasonCode()).isEqualTo("HOLIDAY_NON_CHARGEABLE");
    }
    @Test void chargeableHolidayInheritsSchedule() {
        when(holidays.findByHolidayDateAndZoneIdIsNullAndActiveTrue(any())).thenReturn(List.of(holiday(true,null,null)));
        assertThat(evaluate(60).operational()).isTrue(); assertThat(evaluate(60).holiday()).isTrue();
    }
    @Test void exceptionHoursOverrideNormalCalendar() {
        when(holidays.findByHolidayDateAndZoneIdIsNullAndActiveTrue(any())).thenReturn(List.of(holiday(true,LocalTime.of(12,0),LocalTime.of(15,0))));
        assertThat(evaluate(60).reasonCode()).isEqualTo("OUTSIDE_OPERATION_HOURS");
        var r=service.evaluate(spaceId,null,Instant.parse("2026-10-05T17:00:00Z"),60);
        assertThat(r.operational()).isTrue(); assertThat(r.applicableSchedule().source()).isEqualTo("EXCEPTION");
    }
    @Test void zoneExceptionPrevailsOverGeneralException() {
        when(holidays.findByHolidayDateAndZoneIdAndActiveTrue(any(),eq(zoneId))).thenReturn(List.of(holiday(true,LocalTime.of(9,0),LocalTime.of(12,0))));
        when(holidays.findByHolidayDateAndZoneIdIsNullAndActiveTrue(any())).thenReturn(List.of(holiday(false,null,null)));
        assertThat(evaluate(60).operational()).isTrue();
    }
    @Test void expiredTariff() { tariff.setValidTo(at.minusSeconds(1).atOffset(ZoneOffset.UTC)); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_ACTIVE_TARIFF"); }
    @Test void futureTariff() { tariff.setValidFrom(at.plusSeconds(1).atOffset(ZoneOffset.UTC)); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_ACTIVE_TARIFF"); }
    @Test void missingTariffDoesNotCalculate() { when(tariffs.findActiveTariffsAt(any())).thenReturn(List.of()); var r=evaluate(60); assertThat(r.reasonCode()).isEqualTo("NO_ACTIVE_TARIFF"); assertThat(r.calculatedAmount()).isNull(); }
    @Test void minimumFraction() { var r=evaluate(15); assertThat(r.minimumFractionMinutes()).isEqualTo(15); assertThat(r.calculatedAmount()).isEqualByComparingTo("0.31"); }
    @Test void belowMinimumIsInvalid() { assertThat(evaluate(14).reasonCode()).isEqualTo("INVALID_DURATION"); }
    @Test void exactDuration() { assertThat(evaluate(60).calculatedAmount()).isEqualTo(new BigDecimal("1.23")); }
    @Test void nonMultipleBillsCompleteFraction() { var r=evaluate(16); assertThat(r.billedDurationMinutes()).isEqualTo(30); assertThat(r.calculatedAmount()).isEqualTo(new BigDecimal("0.62")); assertThat(r.expiresAt()).isEqualTo(at.plusSeconds(16*60)); }
    @Test void maximumAllowed() { assertThat(evaluate(180).operational()).isTrue(); }
    @Test void maximumExceeded() { assertThat(evaluate(181).reasonCode()).isEqualTo("MAX_CONTINUOUS_EXCEEDED"); }
    @Test void graceConfigured() { assertThat(evaluate(60).gracePeriodMinutes()).isEqualTo(7); assertThat(service.sessionPolicy(session).gracePeriodMinutes()).isEqualTo(7); }
    @Test void noGraceFailsClosed() { tariff.setGracePeriodMinutes(null); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_GRACE_CONFIGURATION"); assertThat(service.sessionPolicy(session).reasonCode()).isEqualTo("NO_CONTROL_CONFIGURATION"); }
    @Test void missingMaximumFailsClosed() { tariff.setMaxContinuousMinutes(null); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_MAX_CONTINUOUS_CONFIGURATION"); }
    @Test void missingCurrencyOrRoundingFailsClosed() { tariff.setCurrency(null); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_CURRENCY_CONFIGURATION"); tariff.setCurrency("USD"); tariff.setRoundingMode(null); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_ROUNDING_CONFIGURATION"); }
    @Test void zoneTariffPrevailsEvenOverNewerGeneral() {
        Tariff specific = new Tariff(); specific.setId(UUID.randomUUID()); specific.setZoneId(zoneId); specific.setCode("ZONE");
        specific.setAmount(new BigDecimal("2.00")); specific.setDurationMinutes(60); specific.setMinMinutes(15); specific.setMaxContinuousMinutes(180);
        specific.setGracePeriodMinutes(7); specific.setCurrency("USD"); specific.setRoundingMode("HALF_UP"); specific.setValidFrom(at.minusSeconds(86400*2).atOffset(ZoneOffset.UTC));
        when(tariffs.findActiveTariffsAt(any())).thenReturn(List.of(tariff,specific));
        assertThat(evaluate(60).applicableTariff()).isEqualTo("ZONE"); assertThat(evaluate(60).calculatedAmount()).isEqualByComparingTo("2.00");
    }
    @Test void generalCalendarFallbackAndZonePrecedence() {
        Schedule general=new Schedule(); general.setStartTime(LocalTime.of(6,0)); general.setEndTime(LocalTime.of(23,0));
        when(schedules.findByZoneIdIsNullAndDayOfWeekAndActiveTrue(any())).thenReturn(List.of(general));
        assertThat(service.evaluate(spaceId,null,Instant.parse("2026-10-05T13:00:00Z"),60).reasonCode()).isEqualTo("OUTSIDE_OPERATION_HOURS");
        when(schedules.findByZoneIdAndDayOfWeekAndActiveTrue(any(),any())).thenReturn(List.of());
        assertThat(evaluate(60).applicableSchedule().source()).isEqualTo("GENERAL");
    }
    @Test void scheduleValidityRespected() { schedule.setValidFrom(LocalDate.of(2027,1,1)); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_ACTIVE_SCHEDULE"); schedule.setValidFrom(null); schedule.setValidTo(LocalDate.of(2025,12,31)); assertThat(evaluate(60).reasonCode()).isEqualTo("NO_ACTIVE_SCHEDULE"); }
    @Test void configuredRoundingUsesBigDecimal() { tariff.setAmount(new BigDecimal("1.01")); tariff.setRoundingMode("DOWN"); assertThat(evaluate(15).calculatedAmount()).isEqualTo(new BigDecimal("0.25")); tariff.setRoundingMode("UP"); assertThat(evaluate(15).calculatedAmount()).isEqualTo(new BigDecimal("0.26")); }
    @Test void queryBySpaceOrQrMatches() { assertThat(service.evaluate(null,"QR-TEST",at,60)).isEqualTo(evaluate(60)); }
    @Test void extensionWithinAccumulatedMaximum() { var r=service.evaluateExtension(session,90,at); assertThat(r.extensionAllowed()).isTrue(); assertThat(r.expiresAt()).isEqualTo(session.getStartedAt().plusMinutes(180).toInstant()); }
    @Test void extensionBeyondAccumulatedMaximum() { assertThat(service.evaluateExtension(session,91,at).reasonCode()).isEqualTo("MAX_CONTINUOUS_EXCEEDED"); }
    @Test void graceChecksExactSecondsRegardlessOfStoredStatus() {
        session.setExpectedEndAt(at.minusSeconds(7*60).atOffset(ZoneOffset.UTC));
        assertThat(service.evaluateExtension(session,30,at).extensionAllowed()).isTrue();
        session.setExpectedEndAt(at.minusSeconds(7*60+1).atOffset(ZoneOffset.UTC));
        assertThat(service.evaluateExtension(session,30,at).reasonCode()).isEqualTo("EXTENSION_GRACE_EXCEEDED");
    }
    @Test void doesNotSellAcrossClosingTime() { assertThat(service.evaluate(spaceId,null,Instant.parse("2026-10-05T22:30:00Z"),60).reasonCode()).isEqualTo("DURATION_OUTSIDE_OPERATION_HOURS"); }
    @Test void timezoneDeterminesCalendarDate() {
        when(schedules.findByZoneIdAndDayOfWeekAndActiveTrue(eq(zoneId),eq((short)7))).thenReturn(List.of());
        assertThat(service.evaluate(spaceId,null,Instant.parse("2026-10-05T02:00:00Z"),60).reasonCode()).isEqualTo("NO_ACTIVE_SCHEDULE");
        verify(schedules).findByZoneIdAndDayOfWeekAndActiveTrue(zoneId,(short)7);
    }
    @Test void negativeAmountAndInvalidDurationNeverCalculate() { tariff.setAmount(new BigDecimal("-1")); assertThat(evaluate(60).reasonCode()).isEqualTo("INVALID_TARIFF_CONFIGURATION"); tariff.setAmount(BigDecimal.ONE); assertThat(evaluate(0).reasonCode()).isEqualTo("INVALID_DURATION"); }
    @Test void conflictingTariffsFailClosed() { Tariff other=new Tariff(); other.setId(UUID.randomUUID()); other.setValidFrom(tariff.getValidFrom()); when(tariffs.findActiveTariffsAt(any())).thenReturn(List.of(tariff,other)); assertThat(evaluate(60).reasonCode()).isEqualTo("AMBIGUOUS_ACTIVE_TARIFF"); }
}
