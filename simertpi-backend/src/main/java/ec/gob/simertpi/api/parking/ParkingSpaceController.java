package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.ParkingSpaceService;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/parking-spaces")
public class ParkingSpaceController {

    private final ParkingSpaceService parkingSpaceService;

    public ParkingSpaceController(ParkingSpaceService parkingSpaceService) {
        this.parkingSpaceService = parkingSpaceService;
    }

    @PostMapping
    public ResponseEntity<ParkingSpaceResponse> createParkingSpace(
            @Valid @RequestBody CreateParkingSpaceRequest request) {

        ParkingSpace parkingSpace = parkingSpaceService.create(
                request.streetId(),
                request.code(),
                request.qrCode(),
                request.spaceNumber(),
                request.latitude(),
                request.longitude(),
                request.spaceType()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(parkingSpace));
    }

    @GetMapping
    public ResponseEntity<List<ParkingSpaceResponse>> findAll() {

        List<ParkingSpaceResponse> response = parkingSpaceService.findAll()
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<ParkingSpaceResponse> findByCode(
            @PathVariable String code) {

        return parkingSpaceService.findByCode(code)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/qr/{qrCode}")
    public ResponseEntity<ParkingSpaceResponse> findByQrCode(
            @PathVariable String qrCode) {

        return parkingSpaceService.findByQrCode(qrCode)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/street/{streetId}")
    public ResponseEntity<List<ParkingSpaceResponse>> findByStreetId(
            @PathVariable UUID streetId) {

        List<ParkingSpaceResponse> response = parkingSpaceService
                .findByStreetId(streetId)
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    private ParkingSpaceResponse toResponse(ParkingSpace parkingSpace) {

        return new ParkingSpaceResponse(
                parkingSpace.getId(),
                parkingSpace.getStreetId(),
                parkingSpace.getCode(),
                parkingSpace.getQrCode(),
                parkingSpace.getSpaceNumber(),
                parkingSpace.getLatitude(),
                parkingSpace.getLongitude(),
                parkingSpace.getSpaceType(),
                parkingSpace.isActive(),
                parkingSpace.getCreatedAt(),
                parkingSpace.getUpdatedAt()
        );
    }
}
