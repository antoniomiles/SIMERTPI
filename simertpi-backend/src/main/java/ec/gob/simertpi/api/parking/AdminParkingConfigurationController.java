package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.ParkingSpaceService;
import ec.gob.simertpi.application.parking.StreetService;
import ec.gob.simertpi.application.parking.ZoneService;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.entity.Zone;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/parking")
public class AdminParkingConfigurationController {
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final ZoneService zones;
    private final StreetService streets;
    private final ParkingSpaceService spaces;

    public AdminParkingConfigurationController(
            ZoneService zones,
            StreetService streets,
            ParkingSpaceService spaces) {
        this.zones = zones;
        this.streets = streets;
        this.spaces = spaces;
    }

    @GetMapping("/zones")
    public ResponseEntity<List<ZoneResponse>> findZones() {
        List<ZoneResponse> result = zones.findAll().stream()
                .sorted(Comparator.comparing(Zone::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/zones")
    public ResponseEntity<ZoneResponse> createZone(@Valid @RequestBody CreateZoneRequest request) {
        Zone created = zones.create(request.code(), request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(toResponse(created));
    }

    @GetMapping("/zones/{zoneId}/streets")
    public ResponseEntity<List<StreetResponse>> findStreets(@PathVariable UUID zoneId) {
        List<StreetResponse> result = streets.findByZoneId(zoneId).stream()
                .sorted(Comparator.comparing(Street::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/zones/{zoneId}/streets")
    public ResponseEntity<StreetResponse> createStreet(
            @PathVariable UUID zoneId,
            @Valid @RequestBody AdminCreateStreetRequest request) {
        Zone parent = zones.findById(zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("La zona no existe"));
        if (!parent.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede registrar una calle en una zona deshabilitada");
        }
        Street created = streets.create(zoneId, request.code(), request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(toResponse(created));
    }

    @GetMapping("/streets/{streetId}/spaces")
    public ResponseEntity<List<ParkingSpaceResponse>> findSpaces(@PathVariable UUID streetId) {
        List<ParkingSpaceResponse> result = spaces.findByStreetId(streetId).stream()
                .sorted(Comparator.comparing(ParkingSpace::getSpaceNumber, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
    }

    @PostMapping("/streets/{streetId}/spaces")
    public ResponseEntity<ParkingSpaceResponse> createSpace(
            @PathVariable UUID streetId,
            @Valid @RequestBody AdminCreateParkingSpaceRequest request) {
        Street parent = streets.findById(streetId)
                .orElseThrow(() -> new ResourceNotFoundException("La calle no existe"));
        if (!parent.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede registrar un espacio en una calle deshabilitada");
        }
        Zone zone = zones.findById(parent.getZoneId())
                .orElseThrow(() -> new ResourceNotFoundException("La zona no existe"));
        if (!zone.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede registrar un espacio en una zona deshabilitada");
        }
        ParkingSpace created = spaces.create(
                streetId,
                request.code(),
                request.qrCode(),
                request.spaceNumber(),
                request.latitude(),
                request.longitude(),
                request.spaceType());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(toResponse(created));
    }

    private ZoneResponse toResponse(Zone zone) {
        return new ZoneResponse(zone.getId(), zone.getCode(), zone.getName(), zone.getDescription(),
                zone.isActive(), zone.getCreatedAt(), zone.getUpdatedAt());
    }

    private StreetResponse toResponse(Street street) {
        return new StreetResponse(street.getId(), street.getZoneId(), street.getCode(), street.getName(),
                street.getDescription(), street.isActive(), street.getCreatedAt(), street.getUpdatedAt());
    }

    private ParkingSpaceResponse toResponse(ParkingSpace space) {
        return new ParkingSpaceResponse(space.getId(), space.getStreetId(), space.getCode(), space.getQrCode(),
                space.getSpaceNumber(), space.getLatitude(), space.getLongitude(), space.getSpaceType(),
                space.isActive(), space.getCreatedAt(), space.getUpdatedAt());
    }
}
