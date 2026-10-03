package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.api.parking.dto.CreateParkingSessionRequest;
import ec.gob.simertpi.api.parking.dto.ParkingSessionResponse;
import ec.gob.simertpi.api.parking.dto.CreateCitizenParkingSessionRequest;
import ec.gob.simertpi.api.AuthenticationRequiredException;
import ec.gob.simertpi.application.parking.ParkingSessionService;
import ec.gob.simertpi.application.parking.IdempotentParkingSessionCreationService;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ParkingSessionController {

    private final ParkingSessionService parkingSessionService;
    private final IdempotentParkingSessionCreationService idempotentCreationService;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final VehicleRepository vehicleRepository;
    private final TariffRepository tariffRepository;

    public ParkingSessionController(ParkingSessionService parkingSessionService,
                                    IdempotentParkingSessionCreationService idempotentCreationService,
                                    ParkingSpaceRepository parkingSpaceRepository,
                                    VehicleRepository vehicleRepository,
                                    TariffRepository tariffRepository) {
        this.parkingSessionService = parkingSessionService;
        this.idempotentCreationService = idempotentCreationService;
        this.parkingSpaceRepository = parkingSpaceRepository;
        this.vehicleRepository = vehicleRepository;
        this.tariffRepository = tariffRepository;
    }

    @PostMapping("/parking/sessions")
    public ResponseEntity<ParkingSessionResponse> createCitizenSession(
            @Valid @RequestBody CreateCitizenParkingSessionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AuthenticationRequiredException();
        }
        ParkingSession session = idempotentCreationService.create(
                authentication.getName(), idempotencyKey, request.parkingSpaceQrCode(),
                request.vehicleId(), request.tariffId(), request.durationMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(toCitizenResponse(session));
    }

    private ParkingSessionResponse toCitizenResponse(ParkingSession session) {
        var space = parkingSpaceRepository.findById(session.getParkingSpaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Parking space not found"));
        var vehicle = vehicleRepository.findById(session.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
        var tariff = tariffRepository.findById(session.getTariffId())
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        return new ParkingSessionResponse(session.getId(), session.getUserId(), session.getVehicleId(),
                session.getParkingSpaceId(), session.getTariffId(), session.getStartedAt(),
                session.getExpectedEndAt(), session.getEndedAt(), session.getStatus(),
                session.getTotalAmount(), session.getExtensionCount(), session.getCreatedAt(),
                session.getUpdatedAt(), space.getCode(), space.getQrCode(), vehicle.getPlate(),
                tariff.getName(), (int) java.time.Duration.between(session.getStartedAt(),
                session.getExpectedEndAt()).toMinutes());
    }

    @PostMapping
    public ResponseEntity<ParkingSessionResponse> create(
            @Valid @RequestBody CreateParkingSessionRequest request,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AuthenticationRequiredException();
        }
        ParkingSession session = parkingSessionService.createForUsername(
                authentication.getName(),
                request.vehicleId(),
                request.parkingSpaceId(),
                request.tariffId(),
                request.durationMinutes()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(session));
    }

    @PostMapping("/parking-sessions/{id}/close")
    public ResponseEntity<ParkingSessionResponse> close(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        ParkingSession session = parkingSessionService.closeForUsername(id, authentication.getName());
        return ResponseEntity.ok(toResponse(session));
    }
    @GetMapping("/parking-sessions")
    public ResponseEntity<List<ParkingSessionResponse>> findAll(Authentication authentication) {
        return ResponseEntity.ok(
                parkingSessionService.findAllForUsername(authentication.getName())
                        .stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @GetMapping("/parking-sessions/{id}")
    public ResponseEntity<ParkingSessionResponse> findById(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                toResponse(parkingSessionService.findByIdForUsername(id, authentication.getName()))
        );
    }

    @GetMapping("/parking-sessions/user/{userId}")
    public ResponseEntity<List<ParkingSessionResponse>> findByUserId(
            @PathVariable UUID userId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                parkingSessionService.findByUserIdForUsername(userId, authentication.getName())
                        .stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @GetMapping("/parking-sessions/vehicle/{vehicleId}")
    public ResponseEntity<List<ParkingSessionResponse>> findByVehicleId(
            @PathVariable UUID vehicleId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                parkingSessionService.findByVehicleIdForUsername(vehicleId, authentication.getName())
                        .stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    private ParkingSessionResponse toResponse(ParkingSession session) {
        return new ParkingSessionResponse(
                session.getId(),
                session.getUserId(),
                session.getVehicleId(),
                session.getParkingSpaceId(),
                session.getTariffId(),
                session.getStartedAt(),
                session.getExpectedEndAt(),
                session.getEndedAt(),
                session.getStatus(),
                session.getTotalAmount(),
                session.getExtensionCount(),
                session.getCreatedAt(),
                session.getUpdatedAt(), null, null, null, null, null
        );
    }
}
