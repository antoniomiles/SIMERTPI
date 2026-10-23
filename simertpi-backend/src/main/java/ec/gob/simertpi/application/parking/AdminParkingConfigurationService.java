package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.parking.admin.dto.AdminBillingDaysWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.AdminTariffWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.ImmediateTariffRevisionRequest;
import ec.gob.simertpi.api.parking.admin.dto.UpdateBillingDaysRequest;
import ec.gob.simertpi.application.audit.Audited;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class AdminParkingConfigurationService {
    private final TariffRepository tariffs;
    private final ZoneRepository zones;
    private final JdbcTemplate jdbc;
    private final TariffChangeHistoryService tariffHistory;
    private final TariffScopeLockService tariffScopeLocks;
    private final EntityManager entityManager;
    private final BillingCalendarHistoryService billingCalendarHistory;

    public AdminParkingConfigurationService(TariffRepository tariffs, ZoneRepository zones, JdbcTemplate jdbc,
            TariffChangeHistoryService tariffHistory, TariffScopeLockService tariffScopeLocks, EntityManager entityManager,
            BillingCalendarHistoryService billingCalendarHistory) {
        this.tariffs = tariffs; this.zones = zones; this.jdbc = jdbc; this.tariffHistory = tariffHistory;
        this.tariffScopeLocks = tariffScopeLocks; this.entityManager = entityManager; this.billingCalendarHistory = billingCalendarHistory;
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
        if (scoped.size() > 1 && scoped.get(0).getValidFrom().toInstant()
                .equals(scoped.get(1).getValidFrom().toInstant()))
            throw new IllegalStateException("TARIFF_VALIDITY_CONFIGURATION_INVALID");
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
        lockTariffWrite(request.zoneId(), request.code());
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
        tariff.setVersionGroupId(tariff.getId());
        Tariff saved = tariffs.saveAndFlush(tariff);
        tariffHistory.record("CREATED", saved, null, saved.getValidFrom(),
                nonBlankOr(request.reason(), "Creación respaldada por: " + saved.getNormativeReference()), null);
        return saved;
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_ENDED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff endTariff(UUID id, OffsetDateTime effectiveAt, String reason, Long expectedVersion) {
        Tariff beforeLock = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockTariffWrite(beforeLock.getZoneId(), beforeLock.getCode());
        Tariff tariff = tariffs.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!tariff.isActive() || tariff.getCancelledAt() != null || tariff.getValidFrom().isAfter(now)
                || tariff.getValidTo() != null && tariff.getValidTo().isBefore(now)
                || effectiveAt == null || !effectiveAt.isAfter(now)
                || !effectiveAt.isAfter(tariff.getValidFrom()))
            throw new IllegalArgumentException("INVALID_TARIFF_END");
        requireExpectedVersion(tariff, expectedVersion);
        requireReason(reason);
        OffsetDateTime end = effectiveAt.minusNanos(1_000);
        if (tariff.getValidTo() != null && end.isAfter(tariff.getValidTo()))
            throw new IllegalArgumentException("TARIFF_END_AFTER_CURRENT_END");
        ensureNoTariffOverlap(tariff.getZoneId(), tariff.getId(), tariff.getValidFrom(), end);
        Tariff before = copyTariff(tariff);
        tariff.setValidTo(end); tariff.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        Tariff saved = tariffs.saveAndFlush(tariff);
        tariffHistory.record("ENDED", saved, null, effectiveAt, reason, before);
        return saved;
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_ENDED_IMMEDIATELY", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff endTariffImmediately(UUID id, String reason, Long expectedVersion) {
        Tariff beforeLock = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockTariffWrite(beforeLock.getZoneId(), beforeLock.getCode());
        entityManager.refresh(beforeLock, LockModeType.PESSIMISTIC_WRITE);
        Tariff tariff = beforeLock;
        OffsetDateTime effectiveAt = tariffScopeLocks.databaseNow();
        if (!tariff.isActive() || tariff.getCancelledAt() != null || !tariff.getValidFrom().isBefore(effectiveAt)
                || tariff.getValidTo() != null && tariff.getValidTo().isBefore(effectiveAt))
            throw new IllegalArgumentException("TARIFF_NOT_CURRENTLY_EFFECTIVE");
        requireExpectedVersion(tariff, expectedVersion);
        requireReason(reason);
        OffsetDateTime previousEnd = effectiveAt.minusNanos(1_000);
        if (previousEnd.isBefore(tariff.getValidFrom()))
            throw new IllegalArgumentException("TARIFF_IMMEDIATE_END_BEFORE_VALIDITY");
        ensureNoTariffOverlap(tariff.getZoneId(), tariff.getId(), tariff.getValidFrom(), previousEnd);
        Tariff before = copyTariff(tariff);
        tariff.setValidTo(previousEnd);
        tariff.setUpdatedAt(effectiveAt);
        Tariff saved = tariffs.saveAndFlush(tariff);
        tariffHistory.record("ENDED_IMMEDIATELY", saved, null, effectiveAt, reason, before);
        return saved;
    }

    @Transactional
    @Audited(action = "CONFIGURATION_SCHEDULED_TARIFF_UPDATED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff updateScheduledTariff(UUID id, AdminTariffWriteRequest request) {
        Tariff beforeLock = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockTariffWrite(Arrays.asList(beforeLock.getZoneId(), request.zoneId()), beforeLock.getCode(), request.code());
        Tariff tariff = tariffs.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!tariff.isActive() || tariff.getCancelledAt() != null || !tariff.getValidFrom().isAfter(now))
            throw new IllegalArgumentException("TARIFF_SCHEDULE_NO_LONGER_EDITABLE");
        requireExpectedVersion(tariff, request.expectedVersion());
        requireReason(request.reason());
        if (!Objects.equals(tariff.getZoneId(), request.zoneId()) && tariff.getPreviousVersionId() != null)
            throw new IllegalArgumentException("TARIFF_SCOPE_CANNOT_CHANGE");
        validateTariffRequest(request);
        if (request.zoneId() != null) requireZone(request.zoneId());
        if (tariffs.existsByCodeAndVersionGroupIdNot(request.code().trim(), tariff.getVersionGroupId()))
            throw new IllegalArgumentException("TARIFF_CODE_ALREADY_EXISTS");
        boolean overlap = tariffs.findAll().stream().filter(Tariff::isActive).filter(other -> !other.getId().equals(id))
                .filter(other -> Objects.equals(other.getZoneId(), request.zoneId()))
                .anyMatch(other -> overlaps(other.getValidFrom().toInstant(), end(other.getValidTo()),
                        request.validFrom().toInstant(), end(request.validTo())));
        if (overlap) throw new IllegalArgumentException("TARIFF_VALIDITY_OVERLAP");
        Tariff before = copyTariff(tariff);
        BigDecimal amount = new BigDecimal(request.amount());
        tariff.setCode(request.code().trim()); tariff.setName(request.name().trim());
        tariff.setZoneId(request.zoneId());
        tariff.setCurrency(request.currency()); tariff.setAmount(amount);
        tariff.setDurationMinutes(request.durationMinutes()); tariff.setMinMinutes(request.minMinutes());
        tariff.setMaxContinuousMinutes(request.maxContinuousMinutes()); tariff.setGracePeriodMinutes(request.gracePeriodMinutes());
        tariff.setRoundingMode(request.roundingMode()); tariff.setNormativeReference(request.normativeReference().trim());
        tariff.setValidFrom(request.validFrom()); tariff.setValidTo(request.validTo()); tariff.setUpdatedAt(now);
        Tariff saved = tariffs.saveAndFlush(tariff);
        tariffHistory.record("SCHEDULED_UPDATED", saved, null, saved.getValidFrom(), request.reason(), before);
        return saved;
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_REVISED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff reviseCurrentTariff(UUID id, AdminTariffWriteRequest request) {
        Tariff beforeLock = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockTariffWrite(beforeLock.getZoneId(), beforeLock.getCode(), request.code());
        Tariff current = tariffs.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!current.isActive() || current.getCancelledAt() != null || current.getValidFrom().isAfter(now)
                || current.getValidTo() != null && current.getValidTo().isBefore(now))
            throw new IllegalArgumentException("TARIFF_NOT_CURRENTLY_EFFECTIVE");
        requireExpectedVersion(current, request.expectedVersion());
        requireReason(request.reason());
        if (!Objects.equals(current.getZoneId(), request.zoneId()))
            throw new IllegalArgumentException("TARIFF_SCOPE_CANNOT_CHANGE");
        validateTariffRequest(request);
        if (request.validFrom().isAfter(now) && current.getValidTo() != null && request.validFrom().isAfter(current.getValidTo()))
            throw new IllegalArgumentException("TARIFF_REVISION_OUTSIDE_CURRENT_VALIDITY");
        if (tariffs.existsByCodeAndVersionGroupIdNot(request.code().trim(), current.getVersionGroupId()))
            throw new IllegalArgumentException("TARIFF_CODE_ALREADY_EXISTS");
        ensureNoTariffOverlap(current.getZoneId(), current.getId(), request.validFrom(), request.validTo());

        Tariff before = copyTariff(current);
        OffsetDateTime effectiveAt = request.validFrom();
        OffsetDateTime previousEnd = effectiveAt.minusNanos(1_000);
        if (current.getValidTo() != null && current.getValidTo().isBefore(previousEnd)) previousEnd = current.getValidTo();
        current.setValidTo(previousEnd);
        current.setUpdatedAt(now);
        Tariff ended = tariffs.saveAndFlush(current);

        Tariff next = new Tariff();
        next.setId(UUID.randomUUID()); next.setVersionGroupId(current.getVersionGroupId()); next.setPreviousVersionId(current.getId());
        next.setCode(request.code().trim()); next.setName(request.name().trim()); next.setZoneId(current.getZoneId());
        next.setCurrency(request.currency()); next.setAmount(new BigDecimal(request.amount()));
        next.setDurationMinutes(request.durationMinutes()); next.setMinMinutes(request.minMinutes());
        next.setMaxContinuousMinutes(request.maxContinuousMinutes()); next.setGracePeriodMinutes(request.gracePeriodMinutes());
        next.setRoundingMode(request.roundingMode()); next.setNormativeReference(request.normativeReference().trim());
        next.setActive(true); next.setValidFrom(effectiveAt); next.setValidTo(request.validTo());
        next.setCreatedAt(now); next.setUpdatedAt(now);
        Tariff saved = tariffs.saveAndFlush(next);
        tariffHistory.record("REVISED", saved, ended.getId(), effectiveAt, request.reason(), before);
        return saved;
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_REVISED_IMMEDIATELY", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff reviseCurrentTariffImmediately(UUID id, ImmediateTariffRevisionRequest request) {
        Tariff beforeLock = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockTariffWrite(beforeLock.getZoneId(), beforeLock.getCode(), request.code());
        entityManager.refresh(beforeLock, LockModeType.PESSIMISTIC_WRITE);
        Tariff current = beforeLock;
        OffsetDateTime effectiveAt = tariffScopeLocks.databaseNow();
        if (!current.isActive() || current.getCancelledAt() != null || !current.getValidFrom().isBefore(effectiveAt)
                || current.getValidTo() != null && current.getValidTo().isBefore(effectiveAt))
            throw new IllegalArgumentException("TARIFF_NOT_CURRENTLY_EFFECTIVE");
        requireExpectedVersion(current, request.expectedVersion());
        requireReason(request.reason());
        validateTariffEconomics(request.amount(), request.durationMinutes(), request.minMinutes(),
                request.maxContinuousMinutes(), request.currency());
        if (tariffs.existsByCodeAndVersionGroupIdNot(request.code().trim(), current.getVersionGroupId()))
            throw new IllegalArgumentException("TARIFF_CODE_ALREADY_EXISTS");

        OffsetDateTime originalValidTo = current.getValidTo();
        OffsetDateTime previousEnd = effectiveAt.minusNanos(1_000);
        if (previousEnd.isBefore(current.getValidFrom()))
            throw new IllegalArgumentException("TARIFF_IMMEDIATE_REVISION_BEFORE_VALIDITY");
        if (current.getValidTo() != null && previousEnd.isAfter(current.getValidTo()))
            throw new IllegalArgumentException("TARIFF_REVISION_OUTSIDE_CURRENT_VALIDITY");
        ensureNoTariffOverlap(current.getZoneId(), current.getId(), effectiveAt, current.getValidTo());

        Tariff before = copyTariff(current);
        current.setValidTo(previousEnd);
        current.setUpdatedAt(effectiveAt);
        Tariff ended = tariffs.saveAndFlush(current);

        Tariff next = new Tariff();
        next.setId(UUID.randomUUID()); next.setVersionGroupId(current.getVersionGroupId());
        next.setPreviousVersionId(current.getId()); next.setCode(request.code().trim());
        next.setName(request.name().trim()); next.setZoneId(current.getZoneId());
        next.setCurrency(request.currency()); next.setAmount(new BigDecimal(request.amount()));
        next.setDurationMinutes(request.durationMinutes()); next.setMinMinutes(request.minMinutes());
        next.setMaxContinuousMinutes(request.maxContinuousMinutes()); next.setGracePeriodMinutes(request.gracePeriodMinutes());
        next.setRoundingMode(request.roundingMode()); next.setNormativeReference(request.normativeReference().trim());
        next.setActive(true); next.setValidFrom(effectiveAt); next.setValidTo(originalValidTo);
        next.setCreatedAt(effectiveAt); next.setUpdatedAt(effectiveAt);
        Tariff saved = tariffs.saveAndFlush(next);
        tariffHistory.record("REVISED_IMMEDIATELY", saved, ended.getId(), effectiveAt, request.reason(), before);
        return saved;
    }

    @Transactional
    @Audited(action = "CONFIGURATION_SCHEDULED_TARIFF_CANCELLED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public Tariff cancelScheduledTariff(UUID id, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new IllegalArgumentException("TARIFF_CANCELLATION_REASON_REQUIRED");
        Tariff beforeLock = tariffs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        lockTariffWrite(beforeLock.getZoneId(), beforeLock.getCode());
        Tariff tariff = tariffs.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!tariff.isActive() || tariff.getCancelledAt() != null || !tariff.getValidFrom().isAfter(now))
            throw new IllegalArgumentException("TARIFF_SCHEDULE_NO_LONGER_CANCELLABLE");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal()))
            throw new IllegalStateException("ADMIN_ACTOR_REQUIRED_FOR_TARIFF_CANCELLATION");
        UUID actorId = jdbc.query("SELECT id FROM identity.users WHERE username=?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, authentication.getName());
        if (actorId == null) throw new IllegalStateException("ADMIN_ACTOR_NOT_FOUND_FOR_TARIFF_CANCELLATION");
        Tariff before = copyTariff(tariff);
        tariff.setActive(false); tariff.setCancelledAt(now); tariff.setCancelledByUserId(actorId);
        tariff.setCancellationReason(reason.trim()); tariff.setUpdatedAt(now);
        Tariff saved = tariffs.saveAndFlush(tariff);
        tariffHistory.record("SCHEDULED_CANCELLED", saved, null, saved.getValidFrom(), reason, before);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TariffChangeHistoryService.TariffHistoryItem> tariffHistory(UUID id) {
        return tariffHistory.history(id);
    }

    @Transactional(readOnly = true)
    public TariffChangeHistoryService.TariffHistoryPage tariffHistoryPage(UUID tariffId, int page, int size) {
        return tariffHistory.historyPage(tariffId, page, size);
    }

    private void validateTariffRequest(AdminTariffWriteRequest request) {
        validateRange(request.validFrom(), request.validTo());
        if (!request.validFrom().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
            throw new IllegalArgumentException("TARIFF_START_MUST_BE_FUTURE");
        validateTariffEconomics(request.amount(), request.durationMinutes(), request.minMinutes(),
                request.maxContinuousMinutes(), request.currency());
    }

    private void validateTariffEconomics(String rawAmount, Integer durationMinutes, Integer minMinutes,
            Integer maxContinuousMinutes, String currency) {
        BigDecimal amount = new BigDecimal(rawAmount);
        if (amount.signum() <= 0 || amount.precision() > 12 || amount.scale() > 2)
            throw new IllegalArgumentException("INVALID_TARIFF_AMOUNT");
        if (minMinutes > durationMinutes || maxContinuousMinutes < minMinutes)
            throw new IllegalArgumentException("INVALID_TARIFF_DURATION_RANGE");
        try { java.util.Currency.getInstance(currency); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("INVALID_TARIFF_CURRENCY"); }
    }

    @Transactional(readOnly = true)
    public List<BillingCalendarView> billingCalendars() {
        return jdbc.query("""
                SELECT c.id,c.zone_id,z.code AS zone_code,z.name AS zone_name,c.valid_from,c.valid_to,
                       c.normative_reference,c.active,c.created_at,c.updated_at,d.day_of_week,d.chargeable,
                       c.row_version,c.version_group_id,c.previous_calendar_id,c.cancelled_at,c.cancelled_by_user_id,
                       c.cancellation_reason,u.username AS cancelled_by_username
                  FROM parking.billing_day_calendars c
                  LEFT JOIN parking.zones z ON z.id=c.zone_id
                  LEFT JOIN identity.users u ON u.id=c.cancelled_by_user_id
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
                            rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("row_version"),
                            rs.getObject("version_group_id", UUID.class), rs.getObject("previous_calendar_id", UUID.class),
                            rs.getObject("cancelled_at", OffsetDateTime.class), rs.getObject("cancelled_by_user_id", UUID.class),
                            rs.getString("cancellation_reason"), rs.getString("cancelled_by_username")); }
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
        jdbc.update("INSERT INTO parking.billing_day_calendars(id,zone_id,valid_from,valid_to,normative_reference,active,version_group_id) VALUES (?,?,?,?,?,true,?)",
                id, request.zoneId(), request.validFrom(), request.validTo(), request.normativeReference().trim(), id);
        Set<Integer> selected = Set.copyOf(request.chargeableDays());
        for (int day = 1; day <= 7; day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)",
                id, day, selected.contains(day));
        Map<String, Object> after = calendarSnapshot(id);
        billingCalendarHistory.record(id, null, "CREATED", request.validFrom(),
                "Registro inicial; referencia normativa declarada: " + request.normativeReference().trim(), null, after);
        return new BillingCalendarCreated(id);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_BILLING_DAYS_ENDED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public BillingCalendarView updateScheduledBillingCalendar(UUID id, UpdateBillingDaysRequest request) {
        validateCalendarChange(request.validFrom(), request.validTo(), request.chargeableDays(), request.normativeReference(), request.reason());
        LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
        if (!request.validFrom().isAfter(today)) throw new IllegalArgumentException("BILLING_CALENDAR_START_MUST_BE_FUTURE");
        UUID zoneId = calendarScope(id);
        lockScope("billing-days", zoneId);
        CalendarState current = lockCalendar(id);
        requireCalendarVersion(current, request.expectedVersion());
        if (!current.active() || current.cancelledAt() != null || !current.validFrom().isAfter(today))
            throw new IllegalArgumentException("BILLING_CALENDAR_NOT_SCHEDULED");
        ensureNoCalendarOverlap(zoneId, id, request.validFrom(), request.validTo());
        Map<String, Object> before = calendarSnapshot(id);
        int changed = jdbc.update("""
                UPDATE parking.billing_day_calendars SET valid_from=?,valid_to=?,normative_reference=?,updated_at=CURRENT_TIMESTAMP,
                  row_version=row_version+1 WHERE id=? AND active AND row_version=? AND cancelled_at IS NULL
                """, request.validFrom(), request.validTo(), request.normativeReference().trim(), id, request.expectedVersion());
        if (changed != 1) throw new IllegalArgumentException("BILLING_CALENDAR_VERSION_CHANGED_REFRESH_REQUIRED");
        replaceDays(id, request.chargeableDays());
        Map<String, Object> after = calendarSnapshot(id);
        billingCalendarHistory.record(id, current.previousCalendarId(), "SCHEDULED_UPDATED", request.validFrom(), request.reason(), before, after);
        return calendarView(id);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_BILLING_DAYS_VERSIONED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public BillingCalendarView reviseBillingCalendar(UUID id, UpdateBillingDaysRequest request) {
        validateCalendarChange(request.validFrom(), request.validTo(), request.chargeableDays(), request.normativeReference(), request.reason());
        LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
        if (!request.validFrom().isAfter(today)) throw new IllegalArgumentException("BILLING_CALENDAR_START_MUST_BE_FUTURE");
        UUID zoneId = calendarScope(id);
        lockScope("billing-days", zoneId);
        CalendarState current = lockCalendar(id);
        requireCalendarVersion(current, request.expectedVersion());
        if (!current.active() || current.cancelledAt() != null || current.validFrom().isAfter(today)
                || current.validTo() != null && current.validTo().isBefore(today))
            throw new IllegalArgumentException("BILLING_CALENDAR_NOT_CURRENT");
        ensureNoCalendarOverlap(zoneId, id, request.validFrom(), request.validTo());
        Map<String, Object> before = calendarSnapshot(id);
        LocalDate priorEnd = request.validFrom().minusDays(1);
        int changed = jdbc.update("""
                UPDATE parking.billing_day_calendars SET valid_to=CASE WHEN valid_to IS NULL OR valid_to>=? THEN ? ELSE valid_to END,
                  updated_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=? AND active AND row_version=? AND cancelled_at IS NULL
                """, request.validFrom(), priorEnd, id, request.expectedVersion());
        if (changed != 1) throw new IllegalArgumentException("BILLING_CALENDAR_VERSION_CHANGED_REFRESH_REQUIRED");
        UUID newId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking.billing_day_calendars(id,zone_id,valid_from,valid_to,normative_reference,active,version_group_id,previous_calendar_id)
                VALUES (?,?,?,?,?,true,?,?)
                """, newId, zoneId, request.validFrom(), request.validTo(), request.normativeReference().trim(),
                current.versionGroupId(), id);
        replaceDays(newId, request.chargeableDays());
        billingCalendarHistory.record(id, current.previousCalendarId(), "VERSIONED", request.validFrom(), request.reason(), before, calendarSnapshot(id));
        billingCalendarHistory.record(newId, id, "CREATED", request.validFrom(), request.reason(), before, calendarSnapshot(newId));
        return calendarView(newId);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_BILLING_DAYS_ENDED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public BillingCalendarView endBillingCalendar(UUID id, LocalDate effectiveFrom, String reason, Long expectedVersion) {
        requireCalendarReason(reason);
        if (effectiveFrom == null || !effectiveFrom.isAfter(LocalDate.now(ZoneId.of("America/Guayaquil"))))
            throw new IllegalArgumentException("BILLING_CALENDAR_END_MUST_BE_FUTURE");
        UUID zoneId = calendarScope(id);
        lockScope("billing-days", zoneId);
        CalendarState current = lockCalendar(id);
        requireCalendarVersion(current, expectedVersion);
        LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
        if (!current.active() || current.cancelledAt() != null || current.validFrom().isAfter(today)
                || current.validTo() != null && current.validTo().isBefore(today))
            throw new IllegalArgumentException("BILLING_CALENDAR_NOT_CURRENT");
        LocalDate end = effectiveFrom.minusDays(1);
        if (current.validTo() != null && current.validTo().isBefore(effectiveFrom))
            throw new IllegalArgumentException("INVALID_BILLING_CALENDAR_END");
        Map<String, Object> before = calendarSnapshot(id);
        int changed = jdbc.update("UPDATE parking.billing_day_calendars SET valid_to=?,updated_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id=? AND active AND row_version=? AND cancelled_at IS NULL",
                end, id, expectedVersion);
        if (changed != 1) throw new IllegalArgumentException("INVALID_BILLING_CALENDAR_END");
        Map<String, Object> after = calendarSnapshot(id);
        billingCalendarHistory.record(id, current.previousCalendarId(), "ENDED", effectiveFrom, reason, before, after);
        return calendarView(id);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_BILLING_DAYS_CANCELLED", resourceType = "CONFIGURATION", resourceIdArgument = 0)
    public BillingCalendarView cancelScheduledBillingCalendar(UUID id, String reason, Long expectedVersion) {
        requireCalendarReason(reason);
        UUID zoneId = calendarScope(id);
        lockScope("billing-days", zoneId);
        CalendarState current = lockCalendar(id);
        requireCalendarVersion(current, expectedVersion);
        if (!current.active() || current.cancelledAt() != null
                || !current.validFrom().isAfter(LocalDate.now(ZoneId.of("America/Guayaquil"))))
            throw new IllegalArgumentException("BILLING_CALENDAR_NOT_SCHEDULED");
        Map<String, Object> before = calendarSnapshot(id);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID actorId = jdbc.query("SELECT id FROM identity.users WHERE username=?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                authentication == null ? "" : authentication.getName());
        if (actorId == null) throw new IllegalStateException("ADMIN_ACTOR_NOT_FOUND_FOR_BILLING_CALENDAR_CANCELLATION");
        int changed = jdbc.update("""
                UPDATE parking.billing_day_calendars SET active=FALSE,cancelled_at=CURRENT_TIMESTAMP,cancelled_by_user_id=?,
                  cancellation_reason=?,updated_at=CURRENT_TIMESTAMP,row_version=row_version+1
                 WHERE id=? AND active AND cancelled_at IS NULL AND row_version=?
                """, actorId, reason.trim(), id, expectedVersion);
        if (changed != 1) throw new IllegalArgumentException("BILLING_CALENDAR_VERSION_CHANGED_REFRESH_REQUIRED");
        Map<String, Object> after = calendarSnapshot(id);
        billingCalendarHistory.record(id, current.previousCalendarId(), "SCHEDULED_CANCELLED", null, reason, before, after);
        return calendarView(id);
    }

    @Transactional(readOnly = true)
    public List<BillingCalendarHistoryService.BillingCalendarHistoryItem> billingCalendarHistory(UUID id) {
        return billingCalendarHistory.history(id);
    }

    private UUID calendarScope(UUID id) {
        UUID scope = jdbc.query("SELECT zone_id FROM parking.billing_day_calendars WHERE id=?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, id);
        if (scope == null && !jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM parking.billing_day_calendars WHERE id=?)", Boolean.class, id))
            throw new ResourceNotFoundException("Billing calendar not found");
        return scope;
    }

    private CalendarState lockCalendar(UUID id) {
        return jdbc.query("SELECT id,zone_id,valid_from,valid_to,active,row_version,version_group_id,previous_calendar_id,cancelled_at FROM parking.billing_day_calendars WHERE id=? FOR UPDATE",
                rs -> rs.next() ? new CalendarState(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), rs.getBoolean("active"),
                        rs.getLong("row_version"), rs.getObject("version_group_id", UUID.class), rs.getObject("previous_calendar_id", UUID.class),
                        rs.getObject("cancelled_at", OffsetDateTime.class)) : null, id);
    }

    private void requireCalendarVersion(CalendarState state, Long expectedVersion) {
        if (state == null) throw new ResourceNotFoundException("Billing calendar not found");
        if (expectedVersion == null || state.rowVersion() != expectedVersion)
            throw new IllegalArgumentException("BILLING_CALENDAR_VERSION_CHANGED_REFRESH_REQUIRED");
    }

    private void validateCalendarChange(LocalDate from, LocalDate to, List<Integer> days, String reference, String reason) {
        validateRange(from, to); requireCalendarReason(reason);
        if (reference == null || reference.isBlank() || reference.length() > 255) throw new IllegalArgumentException("INVALID_BILLING_CALENDAR_REFERENCE");
        if (days == null || days.stream().anyMatch(d -> d == null || d < 1 || d > 7) || new HashSet<>(days).size() != days.size())
            throw new IllegalArgumentException("INVALID_CHARGEABLE_DAYS");
    }

    private void requireCalendarReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new IllegalArgumentException("BILLING_CALENDAR_CHANGE_REASON_REQUIRED");
    }

    private void ensureNoCalendarOverlap(UUID zoneId, UUID excludedId, LocalDate from, LocalDate to) {
        Boolean overlap = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM parking.billing_day_calendars
                 WHERE active AND cancelled_at IS NULL AND id<>? AND zone_id IS NOT DISTINCT FROM ?
                   AND valid_from<=COALESCE(?,DATE 'infinity') AND COALESCE(valid_to,DATE 'infinity')>=?)
                """, Boolean.class, excludedId, zoneId, to, from);
        if (Boolean.TRUE.equals(overlap)) throw new IllegalArgumentException("BILLING_CALENDAR_VALIDITY_OVERLAP");
    }

    private void replaceDays(UUID id, List<Integer> days) {
        jdbc.update("DELETE FROM parking.billing_day_calendar_days WHERE calendar_id=?", id);
        Set<Integer> selected = Set.copyOf(days);
        for (int day=1; day<=7; day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)", id, day, selected.contains(day));
    }

    private Map<String, Object> calendarSnapshot(UUID id) {
        Map<String,Object> result = new LinkedHashMap<>(jdbc.queryForMap("SELECT id,zone_id,valid_from,valid_to,normative_reference,active,row_version,version_group_id,previous_calendar_id,cancelled_at,cancelled_by_user_id,cancellation_reason FROM parking.billing_day_calendars WHERE id=?", id));
        result.put("days", jdbc.query("SELECT day_of_week,chargeable FROM parking.billing_day_calendar_days WHERE calendar_id=? ORDER BY day_of_week",
                (rs,n) -> Map.of("day",rs.getInt(1),"chargeable",rs.getBoolean(2)), id));
        return result;
    }

    private BillingCalendarView calendarView(UUID id) {
        return billingCalendars().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new ResourceNotFoundException("Billing calendar not found"));
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
    private void requireExpectedVersion(Tariff tariff, Long expectedVersion) {
        if (expectedVersion == null || !Objects.equals(tariff.getRowVersion(), expectedVersion))
            throw new IllegalArgumentException("TARIFF_VERSION_CHANGED_REFRESH_REQUIRED");
    }
    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new IllegalArgumentException("TARIFF_CHANGE_REASON_REQUIRED");
    }
    private String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private void ensureNoTariffOverlap(UUID zoneId, UUID excludedId, OffsetDateTime from, OffsetDateTime to) {
        if (tariffs.findAll().stream().filter(Tariff::isActive).filter(other -> other.getCancelledAt() == null)
                .filter(other -> !other.getId().equals(excludedId)).filter(other -> Objects.equals(other.getZoneId(), zoneId))
                .anyMatch(other -> overlaps(other.getValidFrom().toInstant(), end(other.getValidTo()),
                        from.toInstant(), end(to))))
            throw new IllegalArgumentException("TARIFF_VALIDITY_OVERLAP");
    }
    private Tariff copyTariff(Tariff source) {
        Tariff copy = new Tariff();
        copy.setId(source.getId()); copy.setVersionGroupId(source.getVersionGroupId());
        copy.setPreviousVersionId(source.getPreviousVersionId()); copy.setRowVersion(source.getRowVersion());
        copy.setCode(source.getCode()); copy.setName(source.getName()); copy.setZoneId(source.getZoneId());
        copy.setNormativeReference(source.getNormativeReference()); copy.setCurrency(source.getCurrency());
        copy.setRoundingMode(source.getRoundingMode()); copy.setGracePeriodMinutes(source.getGracePeriodMinutes());
        copy.setAmount(source.getAmount()); copy.setDurationMinutes(source.getDurationMinutes());
        copy.setMinMinutes(source.getMinMinutes()); copy.setMaxContinuousMinutes(source.getMaxContinuousMinutes());
        copy.setActive(source.isActive()); copy.setValidFrom(source.getValidFrom()); copy.setValidTo(source.getValidTo());
        copy.setCreatedAt(source.getCreatedAt()); copy.setUpdatedAt(source.getUpdatedAt());
        copy.setCancelledAt(source.getCancelledAt()); copy.setCancelledByUserId(source.getCancelledByUserId());
        copy.setCancellationReason(source.getCancellationReason());
        return copy;
    }
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
    private void lockTariffWrite(UUID zoneId, String... codes) {
        lockTariffWrite(Collections.singletonList(zoneId), codes);
    }
    private void lockTariffWrite(Collection<UUID> zoneIds, String... codes) {
        tariffScopeLocks.lockScopes(zoneIds);
        tariffScopeLocks.lockVersionCodes(codes);
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
    private record CalendarState(UUID id, UUID zoneId, LocalDate validFrom, LocalDate validTo, boolean active, long rowVersion,
            UUID versionGroupId, UUID previousCalendarId, OffsetDateTime cancelledAt) { }
    public record BillingCalendarView(UUID id, UUID zoneId, String zoneCode, String zoneName, LocalDate validFrom,
            LocalDate validTo, String normativeReference, boolean active, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, Map<Integer, Boolean> days, long rowVersion, UUID versionGroupId,
            UUID previousCalendarId, OffsetDateTime cancelledAt, UUID cancelledByUserId, String cancellationReason,
            String cancelledByUsername) { }
    private static final class MutableCalendar {
        final UUID id, zoneId; final String zoneCode, zoneName, normativeReference; final LocalDate validFrom, validTo;
        final boolean active; final OffsetDateTime createdAt, updatedAt, cancelledAt;
        final long rowVersion; final UUID versionGroupId, previousCalendarId, cancelledByUserId;
        final String cancellationReason, cancelledByUsername; final Map<Integer, Boolean> days = new LinkedHashMap<>();
        MutableCalendar(UUID id, UUID zoneId, String zoneCode, String zoneName, LocalDate validFrom, LocalDate validTo,
                String normativeReference, boolean active, OffsetDateTime createdAt, OffsetDateTime updatedAt, long rowVersion,
                UUID versionGroupId, UUID previousCalendarId, OffsetDateTime cancelledAt, UUID cancelledByUserId,
                String cancellationReason, String cancelledByUsername) {
            this.id=id; this.zoneId=zoneId; this.zoneCode=zoneCode; this.zoneName=zoneName; this.validFrom=validFrom;
            this.validTo=validTo; this.normativeReference=normativeReference; this.active=active; this.createdAt=createdAt; this.updatedAt=updatedAt;
            this.rowVersion=rowVersion; this.versionGroupId=versionGroupId; this.previousCalendarId=previousCalendarId;
            this.cancelledAt=cancelledAt; this.cancelledByUserId=cancelledByUserId; this.cancellationReason=cancellationReason;
            this.cancelledByUsername=cancelledByUsername;
        }
        BillingCalendarView view() { return new BillingCalendarView(id,zoneId,zoneCode,zoneName,validFrom,validTo,normativeReference,
                active,createdAt,updatedAt,Map.copyOf(days),rowVersion,versionGroupId,previousCalendarId,cancelledAt,
                cancelledByUserId,cancellationReason,cancelledByUsername); }
    }
}
