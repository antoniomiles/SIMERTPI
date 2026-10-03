package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.ZoneService;
import ec.gob.simertpi.domain.parking.entity.Zone;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @PostMapping
    public ResponseEntity<ZoneResponse> createZone(
            @Valid @RequestBody CreateZoneRequest request) {

        Zone zone = zoneService.create(
                request.code(),
                request.name(),
                request.description()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(zone));
    }

    @GetMapping
    public ResponseEntity<List<ZoneResponse>> findAll() {

        List<ZoneResponse> response = zoneService.findAll()
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<ZoneResponse> findByCode(
            @PathVariable String code) {

        return zoneService.findByCode(code)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ZoneResponse toResponse(Zone zone) {

        return new ZoneResponse(
                zone.getId(),
                zone.getCode(),
                zone.getName(),
                zone.getDescription(),
                zone.isActive(),
                zone.getCreatedAt(),
                zone.getUpdatedAt()
        );
    }
}