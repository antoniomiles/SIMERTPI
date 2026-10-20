package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.parking.admin.dto.AdminBillingDaysWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.AdminTariffWriteRequest;
import ec.gob.simertpi.application.audit.Audited;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class AdminParkingConfigurationService {
    private final TariffRepository tariffs;
    private final ZoneRepository zones;
    private final JdbcTemplate jdbc;

    public AdminParkingConfigurationService(TariffRepository tariffs, ZoneRepository zones, JdbcTemplate jdbc) {
        this.tariffs = tariffs; this.zones = zones; this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Tariff> tariffs() { return tariffs.findAll(); }

    @Transactional(readOnly = true)
    public EffectiveSnapshot effective(OffsetDateTime at) {
        if (at == null) throw new IllegalArgumentException("EFFECTIVE_AT_REQUIRED");
        Instant instant = at.toInstant();
        LocalDate localDate = instant.atZone(ZoneId.of("America/Guayaquil")).toLocalDate();
        List<Tariff> candidates = tariffs.findActiveTariffsAt(at);
        EffectiveTariff general = effectiveTariff(candidates, null, instant);
        List<EffectiveZone> effectiveZones = zones.findAll().stream().map(zone -> {
            EffectiveTariff zonal = effectiveTariff(candidates, zone.getId(), instant);
            BillingPolicy days = resolveBillingPolicy(zone.getId(), localDate);
            return new EffectiveZone(zone.getId(), zone.getCode(), zone.getName(),
                    zonal != null ? zonal : general, zonal != null ? "ZONE" : general == null ? "UNCONFIGURED" : "GENERAL",
                    days.calendarId(), days.chargeable(), days.configured(), days.configured() && days.zoneId() != null ? "ZONE" : days.configured() ? "GENERAL" : "LEGACY_UNCONFIGURED",
                    days.normativeReference());
        }).toList();
        BillingPolicy generalDays = resolveBillingPolicy(null, localDate);
        return new EffectiveSnapshot(at, general, generalDays.calendarId(), generalDays.chargeable(),
                generalDays.configured(), generalDays.normativeReference(), effectiveZones);
    }

    private EffectiveTariff effectiveTariff(List<Tariff> rows, UUID zoneId, Instant instant) {
        List<Tariff> scoped = rows.stream().filter(t -> Objects.equals(t.getZoneId(), zoneId))
                .filter(t -> t.isActive() && t.getValidFrom() != null && !instant.isBefore(t.getValidFrom().toInstant())
                        && (t.getValidTo() == null || !instant.isAfter(t.getValidTo().toInstant())))
                .sorted(Comparator.comparing(Tariff::getValidFrom).reversed()).toList();
        if (scoped.size() > 1) throw new IllegalStateException("TARIFF_VALIDITY_CONFIGURATION_INVALID");
        return scoped.stream().findFirst()
                .map(t -> new EffectiveTariff(t.getId(), t.getCode(), t.getName(), t.getCurrency(), t.getAmount(),
                        t.getDurationMinutes(), t.getMinMinutes(), t.getMaxContinuousMinutes(), t.getGracePeriodMinutes(),
                        t.getRoundingMode(), t.getValidFrom(), t.getValidTo(), t.getNormativeReference())).orElse(null);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_CREATED", resourceType = "CONFIGURATION")
    public Tariff createTariff(AdminTariffWriteRequest request) {
        validateRange(request.validFrom(), request.validTo());
        if (!request.validFrom().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
            throw new IllegalArgumentException("TARIFF_START_MUST_BE_FUTURE");
        if (request.zoneId() != null) requireZone(request.zoneId());
        BigDecimal amount = new BigDecimal(request.amount());
        if (amount.signum() <= 0 || amount.precision() > 12 || amount.scale() > 2)
            throw new IllegalArgumentException("INVALID_TARIFF_AMOUNT");
        if (request.minMinutes() > request.durationMinutes() || request.maxContinuousMinutes() < request.minMinutes())
            throw new IllegalArgumentException("INVALID_TARIFF_DURATION_RANGE");
        try { java.util.Currency.getInstance(request.currency()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("INVALID_TARIFF_CURRENCY"); }
        lockScope("tariff", request.zoneId());
        if (tariffs.existsByCode(request.code().trim())) throw new IllegalArgumentException("TARIFF_CODE_ALREADY_EXISTS");
        boolean overlap = tariffs.findAll().stream().filter(Tariff::isActive)
                .filter(t -> Objects.equals(t.getZoneId(), request.zoneId()))
                .anyMatch(t -> overlaps(t.getValidFrom().toInstant(), end(t.getValidTo()), request.validFrom().toInstant(), end(request.validTo())));
        if (overlap) throw new IllegalArgumentException("TARIFF_VALIDITY_OVERLAP");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Tariff tariff = new Tariff();
        tariff.setId(UUID.randomUUID()); tariff.setCode(request.code().trim()); tariff.setName(request.name().trim());
        tariff.setZoneId(request.zoneId()); tariff.setCurrency(request.currency()); tariff.setAmount(amount);
        tariff.setDurationMinutes(request.durationMinutes()); tariff.setMinMinutes(request.minMinutes());
        tariff.setMaxContinuousMinutes(request.maxContinuousMinutes()); tariff.setGracePeriodMinutes(request.gracePeriodMinutes());
        tariff.setRoundingMode(request.roundingMode()); tariff.setNormativeReference(request.normativeReference().trim());
        tariff.setActive(true); tariff.setValidFrom(request.validFrom()); tariff.setValidTo(request.validTo());
        tariff.setCreatedAt(now); tariff.setUpdatedAt(now);
        return tariffs.saveAndFlush(tariff);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_ENDED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff endTariff(UUID id, OffsetDateTime effectiveAt) {
        Tariff tariff = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockScope("tariff", tariff.getZoneId());
        tariff = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        if (!tariff.isActive() || effectiveAt == null || !effectiveAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                || !effectiveAt.isAfter(tariff.getValidFrom()))
            throw new IllegalArgumentException("INVALID_TARIFF_END");
        OffsetDateTime end = effectiveAt.minusNanos(1_000);
        if (tariff.getValidTo() != null && end.isAfter(tariff.getValidTo()))
            throw new IllegalArgumentException("TARIFF_END_AFTER_CURRENT_END");
        tariff.setValidTo(end); tariff.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return tariffs.saveAndFlush(tariff);
    }

    @Transactional(readOnly = true)
    public List<BillingCalendarView> billingCalendars() {
        return jdbc.query("""
                SELECT c.id,c.zone_id,z.code AS zone_code,z.name AS zone_name,c.valid_from,c.valid_to,
                       c.normative_reference,c.active,c.created_at,c.updated_at,d.day_of_week,d.chargeable
                  FROM parking.billing_day_calendars c
                  LEFT JOIN parking.zones z ON z.id=c.zone_id
                  LEFT JOIN parking.billing_day_calendar_days d ON d.calendar_id=c.id
                 ORDER BY c.zone_id NULLS FIRST,c.valid_from DESC,d.day_of_week
                """, rs -> {
            Map<UUID, MutableCalendar> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                MutableCalendar row = grouped.computeIfAbsent(id, ignored -> {
                    try { return new MutableCalendar(id, rs.getObject("zone_id", UUID.class), rs.getString("zone_code"),
                            rs.getString("zone_name"), rs.getObject("valid_from", LocalDate.class),
                            rs.getObject("valid_to", LocalDate.class), rs.getString("normative_reference"),
                            rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("updated_at", OffsetDateTime.class)); }
                    catch (java.sql.SQLException ex) { throw new org.springframework.jdbc.UncategorizedSQLException("Read billing calendar", "", ex); }
                });
                Object day = rs.getObject("day_of_week");
                if (day != null) row.days.put(((Number) day).intValue(), rs.getBoolean("chargeable"));
            }
            return grouped.values().stream().map(MutableCalendar::view).toList();
        });
    }

    @Transactional
    @Audited(action = "CONFIGURATION_BILLING_DAYS_CREATED", resourceType = "CONFIGURATION")
    public BillingCalendarCreated createBillingCalendar(AdminBillingDaysWriteRequest request) {
        validateRange(request.validFrom(), request.validTo());
        if (!request.validFrom().isAfter(LocalDate.now(ZoneId.of("America/Guayaquil"))))
            throw new IllegalArgumentException("BILLING_CALENDAR_START_MUST_BE_FUTURE");
        if (request.zoneId() != null) requireZone(request.zoneId());
        if (request.chargeableDays().stream().anyMatch(d -> d == null || d < 1 || d > 7)
                || new HashSet<>(request.chargeableDays()).size() != request.chargeableDays().size())
            throw new IllegalArgumentException("INVALID_CHARGEABLE_DAYS");
        lockScope("billing-days", request.zoneId());
        Boolean overlap = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM parking.billing_day_calendars
                 WHERE active AND zone_id IS NOT DISTINCT FROM ? AND valid_from<=COALESCE(?,DATE 'infinity')
                   AND COALESCE(valid_to,DATE 'infinity')>=?)
                """, Boolean.class, request.zoneId(), request.validTo(), request.validFrom());
        if (Boolean.TRUE.equals(overlap)) throw new IllegalArgumentException("BILLING_CALENDAR_VALIDITY_OVERLAP");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO parking.billing_day_calendars(id,zone_id,valid_from,valid_to,normative_reference,active) VALUES (?,?,?,?,?,true)",
                id, request.zoneId(), request.validFrom(), request.validTo(), request.normativeReference().trim());
        Set<Integer> selected = Set.copyOf(request.chargeableDays());
        for (int day = 1; day <= 7; day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)",
                id, day, selected.contains(day));
        return new BillingCalendarCreated(id);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_BILLING_DAYS_ENDED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public UUID endBillingCalendar(UUID id, LocalDate effectiveFrom) {
        if (effectiveFrom == null || !effectiveFrom.isAfter(LocalDate.now(ZoneId.of("America/Guayaquil"))))
            throw new IllegalArgumentException("BILLING_CALENDAR_END_MUST_BE_FUTURE");
        UUID zoneId = jdbc.query("SELECT zone_id FROM parking.billing_day_calendars WHERE id=?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, id);
        if (zoneId == null && !jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parking.billing_day_calendars WHERE id=? AND zone_id IS NULL)", Boolean.class, id))
            throw new ResourceNotFoundException("Billing calendar not found");
        lockScope("billing-days", zoneId);
        jdbc.query("SELECT id FROM parking.billing_day_calendars WHERE id=? FOR UPDATE", rs -> null, id);
        LocalDate end = effectiveFrom == null ? null : effectiveFrom.minusDays(1);
        int changed = jdbc.update("UPDATE parking.billing_day_calendars SET valid_to=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND active AND valid_from<? AND (valid_to IS NULL OR ?<=valid_to)",
                end, id, effectiveFrom, end);
        if (changed != 1) throw new IllegalArgumentException("INVALID_BILLING_CALENDAR_END");
        return id;
    }

    @Transactional(readOnly = true)
    public BillingPolicy resolveBillingPolicy(UUID zoneId, LocalDate date) {
        List<BillingPolicy> rows = jdbc.query("""
                SELECT c.id,c.zone_id,c.normative_reference,d.day_of_week,d.chargeable
                  FROM parking.billing_day_calendars c
                  JOIN parking.billing_day_calendar_days d ON d.calendar_id=c.id
                 WHERE c.active AND c.valid_from<=? AND (c.valid_to IS NULL OR c.valid_to>=?)
                   AND (c.zone_id=? OR c.zone_id IS NULL) AND d.day_of_week=?
                 ORDER BY (c.zone_id IS NOT NULL) DESC,c.valid_from DESC,c.id
                """, (rs, n) -> new BillingPolicy(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("normative_reference"), rs.getShort("day_of_week") == date.getDayOfWeek().getValue(),
                        rs.getBoolean("chargeable")), date, date, zoneId, (short) date.getDayOfWeek().getValue());
        if (rows.isEmpty()) return new BillingPolicy(null, null, null, true, false);
        BillingPolicy winner = rows.getFirst();
        if (rows.size() > 1 && Objects.equals(winner.zoneId(), rows.get(1).zoneId()))
            throw new IllegalStateException("BILLING_CALENDAR_CONFIGURATION_INVALID");
        return winner;
    }

    private void requireZone(UUID zoneId) { if (!zones.existsById(zoneId)) throw new ResourceNotFoundException("Zone not found"); }
    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to != null && !to.isAfter(from)) throw new IllegalArgumentException("INVALID_CONFIGURATION_VALIDITY");
    }
    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to != null && to.isBefore(from)) throw new IllegalArgumentException("INVALID_CONFIGURATION_VALIDITY");
    }
    private static Instant end(OffsetDateTime to) { return to == null ? Instant.MAX : to.toInstant(); }
    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }
    private void lockScope(String kind, UUID zoneId) {
        String key = kind + ":" + (zoneId == null ? "GENERAL" : zoneId);
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, key); statement.execute(); return null;
            }
        });
    }
    public record BillingPolicy(UUID calendarId, UUID zoneId, String normativeReference, boolean chargeable, boolean configured) { }
    public record BillingCalendarCreated(UUID id) { public UUID getId() { return id; } }
    public record EffectiveTariff(UUID id, String code, String name, String currency, BigDecimal amount, Integer durationMinutes,
            Integer minMinutes, Integer maxContinuousMinutes, Integer gracePeriodMinutes, String roundingMode,
            OffsetDateTime validFrom, OffsetDateTime validTo, String normativeReference) { }
    public record EffectiveZone(UUID zoneId, String zoneCode, String zoneName, EffectiveTariff tariff, String tariffSource,
            UUID billingCalendarId, boolean chargeable, boolean billingDaysConfigured, String billingDaysSource,
            String billingDaysNormativeReference) { }
    public record EffectiveSnapshot(OffsetDateTime at, EffectiveTariff generalTariff, UUID generalBillingCalendarId,
            boolean generalChargeable, boolean generalBillingDaysConfigured, String generalBillingNormativeReference,
            List<EffectiveZone> zones) { }
    public record BillingCalendarView(UUID id, UUID zoneId, String zoneCode, String zoneName, LocalDate validFrom,
            LocalDate validTo, String normativeReference, boolean active, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, Map<Integer, Boolean> days) { }
    private static final class MutableCalendar {
        final UUID id, zoneId; final String zoneCode, zoneName, normativeReference; final LocalDate validFrom, validTo;
        final boolean active; final OffsetDateTime createdAt, updatedAt; final Map<Integer, Boolean> days = new LinkedHashMap<>();
        MutableCalendar(UUID id, UUID zoneId, String zoneCode, String zoneName, LocalDate validFrom, LocalDate validTo,
                String normativeReference, boolean active, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this.id=id; this.zoneId=zoneId; this.zoneCode=zoneCode; this.zoneName=zoneName; this.validFrom=validFrom;
            this.validTo=validTo; this.normativeReference=normativeReference; this.active=active; this.createdAt=createdAt; this.updatedAt=updatedAt;
        }
        BillingCalendarView view() { return new BillingCalendarView(id,zoneId,zoneCode,zoneName,validFrom,validTo,normativeReference,active,createdAt,updatedAt,Map.copyOf(days)); }
    }
}
