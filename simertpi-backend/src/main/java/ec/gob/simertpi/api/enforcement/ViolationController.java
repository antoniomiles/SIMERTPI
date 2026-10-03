package ec.gob.simertpi.api.enforcement;

import ec.gob.simertpi.api.enforcement.dto.ChangeViolationStatusRequest;
import ec.gob.simertpi.api.enforcement.dto.CreateViolationRequest;
import ec.gob.simertpi.api.enforcement.dto.ViolationResponse;
import ec.gob.simertpi.application.enforcement.ViolationService;
import ec.gob.simertpi.application.enforcement.EnforcementIdempotencyService;
import ec.gob.simertpi.domain.enforcement.entity.Violation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/violations")
public class ViolationController {

    private final ViolationService violationService;
    private final EnforcementIdempotencyService idempotencyService;

    public ViolationController(ViolationService violationService,
                               EnforcementIdempotencyService idempotencyService) {
        this.violationService = violationService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<ViolationResponse> create(
            @Valid @RequestBody CreateViolationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        Violation violation = idempotencyService.createViolation(authentication.getName(), idempotencyKey,
                new EnforcementIdempotencyService.ViolationPayload(request.inspectionId(),
                        request.violationType(), request.description(), request.occurredAt()));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(violation));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ViolationResponse> findById(
            @PathVariable UUID id, Authentication authentication
    ) {
        return ResponseEntity.ok(
                toResponse(violationService.findById(authentication.getName(), id))
        );
    }

    @GetMapping("/inspection/{inspectionId}")
    public ResponseEntity<List<ViolationResponse>> findByInspectionId(
            @PathVariable UUID inspectionId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByInspectionId(authentication.getName(), inspectionId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/inspector/{inspectorId}")
    public ResponseEntity<List<ViolationResponse>> findByInspectorId(
            @PathVariable UUID inspectorId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByInspectorId(authentication.getName(), inspectorId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/session/{parkingSessionId}")
    public ResponseEntity<List<ViolationResponse>> findByParkingSessionId(
            @PathVariable UUID parkingSessionId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByParkingSessionId(authentication.getName(), parkingSessionId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/space/{parkingSpaceId}")
    public ResponseEntity<List<ViolationResponse>> findByParkingSpaceId(
            @PathVariable UUID parkingSpaceId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByParkingSpaceId(authentication.getName(), parkingSpaceId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<List<ViolationResponse>> findByVehicleId(
            @PathVariable UUID vehicleId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByVehicleId(authentication.getName(), vehicleId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/plate/{plate}")
    public ResponseEntity<List<ViolationResponse>> findByPlate(
            @PathVariable String plate, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByPlate(authentication.getName(), plate).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<ViolationResponse>> findByStatus(
            @PathVariable String status, Authentication authentication
    ) {
        return ResponseEntity.ok(
                violationService.findByStatus(authentication.getName(), status).stream()
                        .map(this::toResponse).toList()
        );
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ViolationResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeViolationStatusRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                toResponse(violationService.changeStatus(
                        authentication.getName(), id,
                        request.status()
                ))
        );
    }

    private ViolationResponse toResponse(Violation violation) {
        return new ViolationResponse(violation.getId(), violation.getInspectionId(),
                violation.getParkingSessionId(), violation.getParkingSpaceId(), violation.getVehicleId(),
                violation.getPlate(), violation.getViolationType(), violation.getDescription(),
                violation.getOccurredAt(), violation.getStatus(), violation.getFineAmount(),
                violation.getNotifiedAt(), violation.getPaidAt(), violation.getCreatedAt(), violation.getUpdatedAt());
    }
}
