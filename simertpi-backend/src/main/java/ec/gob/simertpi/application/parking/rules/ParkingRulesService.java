package ec.gob.simertpi.application.parking.rules;

import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.parking.calendar.ParkingCalendarService;
import ec.gob.simertpi.application.parking.calendar.ParkingCalendarService.CalendarRules;
import ec.gob.simertpi.domain.parking.entity.*;
import ec.gob.simertpi.domain.parking.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class ParkingRulesService {
    private final ParkingCalendarService calendar;
    private final TariffRepository tariffs;
    private final ParkingSpaceRepository spaces;
    private final StreetRepository streets;
    private final ZoneRepository zones;
    private final ZoneId timeZone;

    public ParkingRulesService(ParkingCalendarService calendar, TariffRepository tariffs,
            ParkingSpaceRepository spaces, StreetRepository streets, ZoneRepository zones,
            @Value("${simertpi.parking.rules.time-zone}") String timeZone) {
        this.calendar = calendar; this.tariffs = tariffs; this.spaces = spaces;
        this.streets = streets; this.zones = zones; this.timeZone = ZoneId.of(timeZone);
    }
    public ParkingRulesResult evaluate(UUID spaceId, String qrCode, Instant at, Integer minutes) {
        if ((spaceId == null) == (qrCode == null || qrCode.isBlank()))
            throw new InvalidRequestException("Provide exactly one of spaceId or qrCode");
        ParkingSpace space = spaceId != null ? spaces.findById(spaceId).orElseThrow(() -> new ResourceNotFoundException("Parking space not found"))
                : spaces.findByQrCode(qrCode.trim()).orElseThrow(() -> new ResourceNotFoundException("Parking space QR code not found"));
        return evaluateSpace(space, at == null ? Instant.now() : at, minutes, null);
    }
    private ParkingRulesResult evaluateSpace(ParkingSpace space, Instant at, Integer minutes, Tariff retainedTariff) {
        Street street = streets.findById(space.getStreetId()).orElseThrow(() -> new ResourceNotFoundException("Street not found"));
        Zone zone = zones.findById(street.getZoneId()).orElseThrow(() -> new ResourceNotFoundException("Zone not found"));
        if (!space.isActive() || !street.isActive() || !zone.isActive())
            return result(zone.getId(), space.getId(), at, minutes, null, null, "INACTIVE_PARKING_LOCATION", null, null, null);
        return resolve(zone.getId(), space.getId(), at, minutes, retainedTariff);
    }
    public ParkingRulesResult evaluateForZone(UUID zoneId, UUID spaceId, Instant at, Integer minutes) {
        return resolve(zoneId, spaceId, at, minutes, null);
    }
    private ParkingRulesResult resolve(UUID zoneId, UUID spaceId, Instant at, Integer minutes, Tariff retainedTariff) {
        ZonedDateTime local = at.atZone(timeZone);
        CalendarRules day = calendar.resolve(zoneId, local.toLocalDate(), local.toLocalTime());
        if (!day.operational()) return result(zoneId, spaceId, at, minutes, day, null, day.reasonCode(), null, null, null);
        List<Tariff> candidates = retainedTariff != null ? List.of() : tariffs.findActiveTariffsAt(at.atOffset(ZoneOffset.UTC)).stream()
                .filter(t -> validAt(t, at) && (t.getZoneId() == null || zoneId.equals(t.getZoneId())))
                .sorted(Comparator.<Tariff, Boolean>comparing(t -> zoneId.equals(t.getZoneId())).reversed()
                        .thenComparing(Tariff::getValidFrom, Comparator.reverseOrder())
                        .thenComparing(t -> String.valueOf(t.getId()))).toList();
        Tariff tariff = retainedTariff;
        if (tariff == null && !candidates.isEmpty()) {
            tariff = candidates.getFirst();
            if (candidates.size() > 1 && Objects.equals(tariff.getZoneId(), candidates.get(1).getZoneId())
                    && tariff.getValidFrom().toInstant().equals(candidates.get(1).getValidFrom().toInstant()))
                return result(zoneId, spaceId, at, minutes, day, null, "AMBIGUOUS_ACTIVE_TARIFF", null, null, null);
        }
        if (tariff == null || !validAt(tariff, at) || (tariff.getZoneId() != null && !zoneId.equals(tariff.getZoneId())))
            return result(zoneId, spaceId, at, minutes, day, null, "NO_ACTIVE_TARIFF", null, null, null);
        String invalid = configurationError(tariff);
        if (invalid != null) return result(zoneId, spaceId, at, minutes, day, tariff, invalid, null, null, null);
        if (minutes == null) return result(zoneId, spaceId, at, null, day, tariff, "RULES_RESOLVED", null, null, null);
        if (minutes < tariff.getMinMinutes()) return result(zoneId, spaceId, at, minutes, day, tariff, "INVALID_DURATION", null, null, null);
        if (minutes > tariff.getMaxContinuousMinutes()) return result(zoneId, spaceId, at, minutes, day, tariff, "MAX_CONTINUOUS_EXCEEDED", null, null, null);
        long billed = billedMinutes(tariff, minutes);
        BigDecimal amount = calculateAmount(tariff, minutes);
        if (amount.precision() > 12) return result(zoneId, spaceId, at, minutes, day, tariff, "AMOUNT_OUT_OF_RANGE", null, null, null);
        Instant expires = at.plus(Duration.ofMinutes(minutes));
        // Do not sell time across a closed interval or into a different calendar day.
        Instant close = local.toLocalDate().atTime(day.schedule().endTime()).atZone(timeZone).toInstant();
        if (expires.isAfter(close)) return result(zoneId, spaceId, at, minutes, day, tariff, "DURATION_OUTSIDE_OPERATION_HOURS", null, null, null);
        return result(zoneId, spaceId, at, minutes, day, tariff, "RULES_RESOLVED", amount, billed, expires);
    }
    public ParkingRulesResult evaluateExtension(ParkingSession session, Integer minutes, Instant at) {
        ParkingSpace space = spaces.findById(session.getParkingSpaceId()).orElseThrow(() -> new ResourceNotFoundException("Parking space not found"));
        Tariff retained = session.getTariffId() == null ? null : tariffs.findById(session.getTariffId()).orElse(null);
        var quote = evaluateSpace(space, at, minutes, retained);
        String reason = quote.reasonCode();
        Instant expires = null;
        if (retained == null) reason = "NO_ACTIVE_TARIFF";
        else if (minutes == null || minutes <= 0 || session.getStartedAt() == null || session.getExpectedEndAt() == null) reason = "INVALID_DURATION";
        else if (quote.operational()) {
            expires = session.getExpectedEndAt().toInstant().plus(Duration.ofMinutes(minutes));
            Duration total = Duration.between(session.getStartedAt().toInstant(), expires);
            if (total.isNegative() || total.compareTo(Duration.ofMinutes(quote.maximumContinuousMinutes())) > 0) reason = "MAX_CONTINUOUS_EXCEEDED";
            else if (!expires.isAfter(at)) reason = "EXTENSION_END_NOT_FUTURE";
            else if (at.isAfter(session.getExpectedEndAt().toInstant().plus(Duration.ofMinutes(quote.gracePeriodMinutes())))) reason = "EXTENSION_GRACE_EXCEEDED";
            else if (expires.isAfter(quote.evaluatedAt().toLocalDate().atTime(quote.applicableSchedule().endTime()).atZone(timeZone).toInstant())) reason = "DURATION_OUTSIDE_OPERATION_HOURS";
        }
        return new ParkingRulesResult(quote.zoneId(), quote.spaceId(), quote.evaluatedAt(), quote.chargeable(),
                "RULES_RESOLVED".equals(reason), quote.holiday(), quote.applicableSchedule(), quote.applicableTariff(),
                quote.minimumFractionMinutes(), quote.maximumContinuousMinutes(), quote.gracePeriodMinutes(), quote.currency(),
                quote.unitPrice(), quote.unitDurationMinutes(), "RULES_RESOLVED".equals(reason) ? quote.calculatedAmount() : null,
                minutes, quote.billedDurationMinutes(), "RULES_RESOLVED".equals(reason) ? expires : null,
                "RULES_RESOLVED".equals(reason), reason, quote.tariffId());
    }
    // Existing sessions retain the policy of their referenced tariff, even after its sales validity ends.
    public SessionPolicy sessionPolicy(ParkingSession session) {
        Tariff tariff = session.getTariffId() == null ? null : tariffs.findById(session.getTariffId()).orElse(null);
        Integer max = tariff == null ? null : tariff.getMaxContinuousMinutes();
        Integer grace = tariff == null ? null : tariff.getGracePeriodMinutes();
        return new SessionPolicy(max != null && max > 0 ? max : null, grace != null && grace >= 0 ? grace : null,
                max == null || max <= 0 || grace == null || grace < 0 ? "NO_CONTROL_CONFIGURATION" : "RULES_RESOLVED");
    }
    private boolean validAt(Tariff t, Instant at) {
        return t.isActive() && t.getValidFrom() != null && !at.isBefore(t.getValidFrom().toInstant())
                && (t.getValidTo() == null || !at.isAfter(t.getValidTo().toInstant()));
    }
    private String configurationError(Tariff t) {
        if (t.getAmount() == null || t.getAmount().signum() < 0 || t.getDurationMinutes() == null || t.getDurationMinutes() <= 0
                || t.getMinMinutes() == null || t.getMinMinutes() <= 0) return "INVALID_TARIFF_CONFIGURATION";
        if (t.getMaxContinuousMinutes() == null || t.getMaxContinuousMinutes() <= 0) return "NO_MAX_CONTINUOUS_CONFIGURATION";
        if (t.getGracePeriodMinutes() == null || t.getGracePeriodMinutes() < 0) return "NO_GRACE_CONFIGURATION";
        if (t.getCurrency() == null) return "NO_CURRENCY_CONFIGURATION";
        try { Currency.getInstance(t.getCurrency()); } catch (IllegalArgumentException invalid) { return "INVALID_CURRENCY_CONFIGURATION"; }
        if (t.getRoundingMode() == null) return "NO_ROUNDING_CONFIGURATION";
        try { RoundingMode.valueOf(t.getRoundingMode()); } catch (IllegalArgumentException invalid) { return "INVALID_ROUNDING_CONFIGURATION"; }
        return null;
    }
    private long billedMinutes(Tariff tariff, long minutes) {
        return Math.multiplyExact(Math.floorDiv(Math.addExact(minutes, tariff.getMinMinutes() - 1L), tariff.getMinMinutes()), tariff.getMinMinutes());
    }
    public BigDecimal calculateAmount(Tariff tariff, long minutes) {
        if (minutes <= 0 || tariff.getMinMinutes() == null || tariff.getMinMinutes() <= 0 || tariff.getDurationMinutes() == null
                || tariff.getDurationMinutes() <= 0 || tariff.getAmount() == null || tariff.getAmount().signum() < 0 || tariff.getRoundingMode() == null)
            throw new IllegalArgumentException("INVALID_TARIFF_CONFIGURATION");
        return tariff.getAmount().multiply(BigDecimal.valueOf(billedMinutes(tariff, minutes)))
                .divide(BigDecimal.valueOf(tariff.getDurationMinutes()), 2, RoundingMode.valueOf(tariff.getRoundingMode()));
    }
    private ParkingRulesResult result(UUID zone, UUID space, Instant at, Integer minutes, CalendarRules day, Tariff t,
            String reason, BigDecimal amount, Long billed, Instant expires) {
        boolean resolved = "RULES_RESOLVED".equals(reason);
        return new ParkingRulesResult(zone, space, at.atZone(timeZone), day != null && day.operational() && t != null,
                resolved, day != null && day.holiday(), day == null ? null : day.schedule(), t == null ? null : t.getCode(),
                t == null ? null : t.getMinMinutes(), t == null ? null : t.getMaxContinuousMinutes(), t == null ? null : t.getGracePeriodMinutes(),
                t == null ? null : t.getCurrency(), t == null ? null : t.getAmount(), t == null ? null : t.getDurationMinutes(),
                amount, minutes, billed, expires, resolved, reason, t == null ? null : t.getId());
    }
    public record SessionPolicy(Integer maximumContinuousMinutes, Integer gracePeriodMinutes, String reasonCode) { }
}
