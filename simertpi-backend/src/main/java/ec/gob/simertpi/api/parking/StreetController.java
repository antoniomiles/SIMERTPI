package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.StreetService;
import ec.gob.simertpi.domain.parking.entity.Street;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/streets")
public class StreetController {

    private final StreetService streetService;

    public StreetController(StreetService streetService) {
        this.streetService = streetService;
    }

    @PostMapping
    public ResponseEntity<StreetResponse> createStreet(
            @Valid @RequestBody CreateStreetRequest request) {

        Street street = streetService.create(
                request.zoneId(),
                request.code(),
                request.name(),
                request.description()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(street));
    }

    @GetMapping
    public ResponseEntity<List<StreetResponse>> findAll() {

        List<StreetResponse> response = streetService.findAll()
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/zone/{zoneId}/code/{code}")
    public ResponseEntity<StreetResponse> findByZoneIdAndCode(
            @PathVariable UUID zoneId,
            @PathVariable String code) {

        return streetService.findByZoneIdAndCode(zoneId, code)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/zone/{zoneId}")
    public ResponseEntity<List<StreetResponse>> findByZoneId(
            @PathVariable UUID zoneId) {

        List<StreetResponse> response = streetService.findByZoneId(zoneId)
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    private StreetResponse toResponse(Street street) {

        return new StreetResponse(
                street.getId(),
                street.getZoneId(),
                street.getCode(),
                street.getName(),
                street.getDescription(),
                street.isActive(),
                street.getCreatedAt(),
                street.getUpdatedAt()
        );
    }
}
