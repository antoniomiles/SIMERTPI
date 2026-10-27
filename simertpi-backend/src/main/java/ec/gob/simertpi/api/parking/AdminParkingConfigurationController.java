package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.api.parking.admin.dto.AdminBillingDaysWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.AdminTariffWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.ImmediateTariffRevisionRequest;
import ec.gob.simertpi.api.parking.admin.dto.UpdateBillingDaysRequest;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.parking.AdminParkingConfigurationService;
import ec.gob.simertpi.application.parking.TariffChangeHistoryService;
import ec.gob.simertpi.application.parking.ParkingSpaceService;
import ec.gob.simertpi.application.parking.AdminParkingCatalogService;
import ec.gob.simertpi.application.parking.AdminCatalogQueryService;
import ec.gob.simertpi.application.parking.StreetService;
import ec.gob.simertpi.application.parking.ZoneService;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.entity.Zone;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/parking")
public class AdminParkingConfigurationController {
    private final AdminParkingConfigurationService service;
    private final ZoneService zones;
    private final StreetService streets;
    private final ParkingSpaceService spaces;
    private final AdminParkingCatalogService catalog;
    private final AdminCatalogQueryService catalogQuery;
    public AdminParkingConfigurationController(AdminParkingConfigurationService service, ZoneService zones,
            StreetService streets, ParkingSpaceService spaces, AdminParkingCatalogService catalog, AdminCatalogQueryService catalogQuery) {
        this.service = service; this.zones = zones; this.streets = streets; this.spaces = spaces; this.catalog = catalog; this.catalogQuery=catalogQuery;
    }

    @GetMapping("/zones")
    public ResponseEntity<List<ZoneResponse>> findZones() {
        return noStore(zones.findAll().stream().sorted(Comparator.comparing(Zone::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse).toList());
    }

    @GetMapping("/catalog/zones")
    public ResponseEntity<AdminCatalogQueryService.Page<AdminCatalogQueryService.ZoneRow>> catalogZones(
            @RequestParam(required=false) String q, @RequestParam(required=false) Boolean active,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return noStore(catalogQuery.zones(q, active, page, size));
    }

    @GetMapping("/catalog/streets")
    public ResponseEntity<AdminCatalogQueryService.Page<AdminCatalogQueryService.StreetRow>> catalogStreets(
            @RequestParam(required=false) String q, @RequestParam(required=false) UUID zoneId,
            @RequestParam(required=false) Boolean active, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size) {
        return noStore(catalogQuery.streets(q, zoneId, active, page, size));
    }

    @GetMapping("/catalog/spaces")
    public ResponseEntity<AdminCatalogQueryService.Page<AdminCatalogQueryService.SpaceRow>> catalogSpaces(
            @RequestParam(required=false) String q, @RequestParam(required=false) UUID zoneId,
            @RequestParam(required=false) UUID streetId, @RequestParam(required=false) Boolean active,
            @RequestParam(required=false) String operational, @RequestParam(required=false) Boolean georeferenced,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return noStore(catalogQuery.spaces(q, zoneId, streetId, active, operational, georeferenced, page, size));
    }

    @GetMapping("/catalog/map")
    public ResponseEntity<AdminCatalogQueryService.MapCoverage> catalogMap(
            @RequestParam(required=false) UUID zoneId, @RequestParam(required=false) UUID streetId,
            @RequestParam(defaultValue="1000") int limit) {
        return noStore(catalogQuery.mapCoverage(zoneId, streetId, limit));
    }

    @GetMapping("/catalog/spaces/nearby")
    public ResponseEntity<List<AdminCatalogQueryService.NearbySpace>> nearbySpaces(
            @RequestParam java.math.BigDecimal latitude, @RequestParam java.math.BigDecimal longitude,
            @RequestParam(required=false) UUID excludeId, @RequestParam(defaultValue="3") double radiusMeters) {
        return noStore(catalogQuery.nearby(latitude, longitude, excludeId, radiusMeters));
    }

    @PostMapping("/zones")
    public ResponseEntity<ZoneResponse> createZone(@Valid @RequestBody CreateZoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(toResponse(zones.create(request.code(), request.name(), request.description())));
    }

    @PutMapping("/zones/{zoneId}")
    public ResponseEntity<ZoneResponse> updateZone(@PathVariable UUID zoneId,
            @Valid @RequestBody AdminCatalogUpdateRequests.ZoneUpdate request) {
        return noStore(toResponse(catalog.updateZone(zoneId, request)));
    }

    @GetMapping("/zones/{zoneId}/streets")
    public ResponseEntity<List<StreetResponse>> findStreets(@PathVariable UUID zoneId) {
        return noStore(streets.findByZoneId(zoneId).stream().sorted(Comparator.comparing(Street::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse).toList());
    }

    @PostMapping("/zones/{zoneId}/streets")
    public ResponseEntity<StreetResponse> createStreet(@PathVariable UUID zoneId, @Valid @RequestBody AdminCreateStreetRequest request) {
        Zone parent = zones.findById(zoneId).orElseThrow(() -> new ResourceNotFoundException("La zona no existe"));
        if (!parent.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede registrar una calle en una zona deshabilitada");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(toResponse(streets.create(zoneId, request.code(), request.name(), request.description())));
    }

    @PutMapping("/streets/{streetId}")
    public ResponseEntity<StreetResponse> updateStreet(@PathVariable UUID streetId,
            @Valid @RequestBody AdminCatalogUpdateRequests.StreetUpdate request) {
        return noStore(toResponse(catalog.updateStreet(streetId, request)));
    }

    @GetMapping("/streets/{streetId}/spaces")
    public ResponseEntity<List<ParkingSpaceResponse>> findSpaces(@PathVariable UUID streetId) {
        return noStore(spaces.findByStreetId(streetId).stream()
                .sorted(Comparator.comparing(ParkingSpace::getSpaceNumber, String.CASE_INSENSITIVE_ORDER)).map(this::toResponse).toList());
    }

    @PostMapping("/streets/{streetId}/spaces")
    public ResponseEntity<ParkingSpaceResponse> createSpace(@PathVariable UUID streetId,
            @Valid @RequestBody AdminCreateParkingSpaceRequest request) {
        Street parent = streets.findById(streetId).orElseThrow(() -> new ResourceNotFoundException("La calle no existe"));
        if (!parent.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede registrar un espacio en una calle deshabilitada");
        Zone zone = zones.findById(parent.getZoneId()).orElseThrow(() -> new ResourceNotFoundException("La zona no existe"));
        if (!zone.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede registrar un espacio en una zona deshabilitada");
        // Administrative QR identities are server-generated from the newly assigned UUID.
        ParkingSpace created = spaces.create(streetId, request.code(), null, request.spaceNumber(),
                request.latitude(), request.longitude(), request.spaceType());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toResponse(created));
    }

    @PutMapping("/spaces/{spaceId}")
    public ResponseEntity<ParkingSpaceResponse> updateSpace(@PathVariable UUID spaceId,
            @Valid @RequestBody AdminCatalogUpdateRequests.SpaceUpdate request) {
        return noStore(toResponse(catalog.updateSpace(spaceId, request)));
    }

    @GetMapping("/billing-day-calendars")
    public ResponseEntity<List<AdminParkingConfigurationService.BillingCalendarView>> billingCalendars() {
        return noStore(service.billingCalendars());
    }

    @GetMapping("/billing-day-calendars/{id}/history")
    public ResponseEntity<List<ec.gob.simertpi.application.parking.BillingCalendarHistoryService.BillingCalendarHistoryItem>> billingCalendarHistory(@PathVariable UUID id) {
        return noStore(service.billingCalendarHistory(id));
    }

    @GetMapping("/tariffs/{id}/history")
    public ResponseEntity<List<TariffChangeHistoryService.TariffHistoryItem>> tariffHistory(@PathVariable UUID id) {
        return noStore(service.tariffHistory(id));
    }

    @GetMapping("/tariffs/history")
    public ResponseEntity<TariffChangeHistoryService.TariffHistoryPage> tariffHistoryPage(
            @RequestParam(required = false) UUID tariffId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noStore(service.tariffHistoryPage(tariffId, page, size));
    }

    @GetMapping("/configuration/effective")
    public ResponseEntity<AdminParkingConfigurationService.EffectiveSnapshot> effective(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return noStore(service.effective(at));
    }

    @PostMapping("/tariffs")
    public ResponseEntity<AdminTariffView> createTariff(@Valid @RequestBody AdminTariffWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(AdminTariffView.of(service.createTariff(request)));
    }

    @PostMapping("/tariffs/{id}/end")
    public ResponseEntity<AdminTariffView> endTariff(@PathVariable UUID id, @Valid @RequestBody EndTariffRequest request) {
        return noStore(AdminTariffView.of(service.endTariff(id, request.effectiveAt(), request.reason(), request.expectedVersion())));
    }

    @PostMapping("/tariffs/{id}/end-now")
    public ResponseEntity<AdminTariffView> endTariffImmediately(@PathVariable UUID id,
            @Valid @RequestBody ImmediateTariffEndRequest request) {
        return noStore(AdminTariffView.of(service.endTariffImmediately(id, request.reason(), request.expectedVersion())));
    }

    @PostMapping("/tariffs/{id}/revise")
    public ResponseEntity<AdminTariffView> reviseTariff(@PathVariable UUID id,
            @Valid @RequestBody AdminTariffWriteRequest request) {
        return noStore(AdminTariffView.of(service.reviseCurrentTariff(id, request)));
    }

    @PostMapping("/tariffs/{id}/revise-now")
    public ResponseEntity<AdminTariffView> reviseTariffImmediately(@PathVariable UUID id,
            @Valid @RequestBody ImmediateTariffRevisionRequest request) {
        return noStore(AdminTariffView.of(service.reviseCurrentTariffImmediately(id, request)));
    }

    @PutMapping("/tariffs/{id}")
    public ResponseEntity<AdminTariffView> updateScheduledTariff(@PathVariable UUID id,
            @Valid @RequestBody AdminTariffWriteRequest request) {
        return noStore(AdminTariffView.of(service.updateScheduledTariff(id, request)));
    }

    @PostMapping("/tariffs/{id}/cancel")
    public ResponseEntity<AdminTariffView> cancelScheduledTariff(@PathVariable UUID id,
            @Valid @RequestBody CancelTariffRequest request) {
        return noStore(AdminTariffView.of(service.cancelScheduledTariff(id, request.reason())));
    }

    @PostMapping("/billing-day-calendars")
    public ResponseEntity<UUID> createBillingCalendar(@Valid @RequestBody AdminBillingDaysWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.createBillingCalendar(request).id());
    }

    @PutMapping("/billing-day-calendars/{id}")
    public ResponseEntity<AdminParkingConfigurationService.BillingCalendarView> updateScheduledBillingCalendar(
            @PathVariable UUID id, @Valid @RequestBody UpdateBillingDaysRequest request) {
        return noStore(service.updateScheduledBillingCalendar(id, request));
    }

    @PostMapping("/billing-day-calendars/{id}/revise")
    public ResponseEntity<AdminParkingConfigurationService.BillingCalendarView> reviseBillingCalendar(
            @PathVariable UUID id, @Valid @RequestBody UpdateBillingDaysRequest request) {
        return noStore(service.reviseBillingCalendar(id, request));
    }

    @PostMapping("/billing-day-calendars/{id}/end")
    public ResponseEntity<AdminParkingConfigurationService.BillingCalendarView> endBillingCalendar(@PathVariable UUID id,
            @Valid @RequestBody EndBillingDaysRequest request) {
        return noStore(service.endBillingCalendar(id, request.effectiveFrom(), request.reason(), request.expectedVersion()));
    }

    @PostMapping("/billing-day-calendars/{id}/cancel")
    public ResponseEntity<AdminParkingConfigurationService.BillingCalendarView> cancelBillingCalendar(@PathVariable UUID id,
            @Valid @RequestBody CancelBillingDaysRequest request) {
        return noStore(service.cancelScheduledBillingCalendar(id, request.reason(), request.expectedVersion()));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private ZoneResponse toResponse(Zone zone) { return new ZoneResponse(zone.getId(), zone.getCode(), zone.getName(),
            zone.getDescription(), zone.isActive(), zone.getCreatedAt(), zone.getUpdatedAt()); }
    private StreetResponse toResponse(Street street) { return new StreetResponse(street.getId(), street.getZoneId(), street.getCode(),
            street.getName(), street.getDescription(), street.isActive(), street.getCreatedAt(), street.getUpdatedAt()); }
    private ParkingSpaceResponse toResponse(ParkingSpace space) { return new ParkingSpaceResponse(space.getId(), space.getStreetId(),
            space.getCode(), space.getQrCode(), space.getSpaceNumber(), space.getLatitude(), space.getLongitude(), space.getSpaceType(),
            space.isActive(), space.getCreatedAt(), space.getUpdatedAt()); }

    public record EndTariffRequest(@jakarta.validation.constraints.NotNull OffsetDateTime effectiveAt,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion) { }
    public record ImmediateTariffEndRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion) { }
    public record CancelTariffRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) { }
    public record EndBillingDaysRequest(@jakarta.validation.constraints.NotNull LocalDate effectiveFrom,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion) { }
    public record CancelBillingDaysRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedVersion) { }
    public record AdminTariffView(UUID id, String code, String name, UUID zoneId, String currency,
            java.math.BigDecimal amount, Integer durationMinutes, Integer minMinutes, Integer maxContinuousMinutes,
            Integer gracePeriodMinutes, String roundingMode, String normativeReference, boolean active,
            OffsetDateTime validFrom, OffsetDateTime validTo, OffsetDateTime cancelledAt,
            UUID cancelledByUserId, String cancellationReason, Long rowVersion, UUID versionGroupId,
            UUID previousVersionId) {
        static AdminTariffView of(Tariff t) { return new AdminTariffView(t.getId(),t.getCode(),t.getName(),t.getZoneId(),
                t.getCurrency(),t.getAmount(),t.getDurationMinutes(),t.getMinMinutes(),t.getMaxContinuousMinutes(),
                t.getGracePeriodMinutes(),t.getRoundingMode(),t.getNormativeReference(),t.isActive(),t.getValidFrom(),t.getValidTo(),
                t.getCancelledAt(),t.getCancelledByUserId(),t.getCancellationReason(),t.getRowVersion(),
                t.getVersionGroupId(),t.getPreviousVersionId()); }
    }
}
