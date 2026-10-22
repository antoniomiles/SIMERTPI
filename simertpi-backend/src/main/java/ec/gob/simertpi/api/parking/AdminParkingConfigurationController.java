package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.api.parking.admin.dto.AdminBillingDaysWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.AdminTariffWriteRequest;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.parking.AdminParkingConfigurationService;
import ec.gob.simertpi.application.parking.ParkingSpaceService;
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
    public AdminParkingConfigurationController(AdminParkingConfigurationService service, ZoneService zones,
            StreetService streets, ParkingSpaceService spaces) {
        this.service = service; this.zones = zones; this.streets = streets; this.spaces = spaces;
    }

    @GetMapping("/zones")
    public ResponseEntity<List<ZoneResponse>> findZones() {
        return noStore(zones.findAll().stream().sorted(Comparator.comparing(Zone::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse).toList());
    }

    @PostMapping("/zones")
    public ResponseEntity<ZoneResponse> createZone(@Valid @RequestBody CreateZoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(toResponse(zones.create(request.code(), request.name(), request.description())));
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
        ParkingSpace created = spaces.create(streetId, request.code(), request.qrCode(), request.spaceNumber(),
                request.latitude(), request.longitude(), request.spaceType());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toResponse(created));
    }

    @GetMapping("/billing-day-calendars")
    public ResponseEntity<List<AdminParkingConfigurationService.BillingCalendarView>> billingCalendars() {
        return noStore(service.billingCalendars());
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
        return noStore(AdminTariffView.of(service.endTariff(id, request.effectiveAt())));
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

    @PostMapping("/billing-day-calendars/{id}/end")
    public ResponseEntity<UUID> endBillingCalendar(@PathVariable UUID id, @Valid @RequestBody EndBillingDaysRequest request) {
        return noStore(service.endBillingCalendar(id, request.effectiveFrom()));
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

    public record EndTariffRequest(@jakarta.validation.constraints.NotNull OffsetDateTime effectiveAt) { }
    public record CancelTariffRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) { }
    public record EndBillingDaysRequest(@jakarta.validation.constraints.NotNull LocalDate effectiveFrom) { }
    public record AdminTariffView(UUID id, String code, String name, UUID zoneId, String currency,
            java.math.BigDecimal amount, Integer durationMinutes, Integer minMinutes, Integer maxContinuousMinutes,
            Integer gracePeriodMinutes, String roundingMode, String normativeReference, boolean active,
            OffsetDateTime validFrom, OffsetDateTime validTo, OffsetDateTime cancelledAt,
            UUID cancelledByUserId, String cancellationReason) {
        static AdminTariffView of(Tariff t) { return new AdminTariffView(t.getId(),t.getCode(),t.getName(),t.getZoneId(),
                t.getCurrency(),t.getAmount(),t.getDurationMinutes(),t.getMinMinutes(),t.getMaxContinuousMinutes(),
                t.getGracePeriodMinutes(),t.getRoundingMode(),t.getNormativeReference(),t.isActive(),t.getValidFrom(),t.getValidTo(),
                t.getCancelledAt(),t.getCancelledByUserId(),t.getCancellationReason()); }
    }
}
