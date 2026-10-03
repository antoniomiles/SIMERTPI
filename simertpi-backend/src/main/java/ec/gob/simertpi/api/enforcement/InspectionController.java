package ec.gob.simertpi.api.enforcement;

import ec.gob.simertpi.api.enforcement.dto.CreateInspectionRequest;
import ec.gob.simertpi.api.enforcement.dto.InspectionSituationResponse;
import ec.gob.simertpi.api.enforcement.dto.InspectionResponse;
import ec.gob.simertpi.application.enforcement.InspectionService;
import ec.gob.simertpi.application.enforcement.EnforcementIdempotencyService;
import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {

    private final InspectionService inspectionService;
    private final EnforcementIdempotencyService idempotencyService;

    public InspectionController(
            InspectionService inspectionService,
            EnforcementIdempotencyService idempotencyService
    ) {
        this.inspectionService = inspectionService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<InspectionResponse> create(
            @Valid @RequestBody CreateInspectionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        Inspection inspection = idempotencyService.createInspection(
                authentication.getName(), idempotencyKey,
                new EnforcementIdempotencyService.InspectionPayload(
                request.parkingSessionId(),
                request.parkingSpaceId(),
                request.vehicleId(),
                request.observedPlate(),
                request.observedAt(),
                request.result(),
                request.notes(),
                request.latitude(),
                request.longitude()
                ));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(inspection));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InspectionResponse> findById(
            @PathVariable UUID id, Authentication authentication
    ) {
        return ResponseEntity.ok(
                toResponse(inspectionService.findById(authentication.getName(), id))
        );
    }

    @GetMapping("/inspector/{inspectorId}")
    public ResponseEntity<List<InspectionResponse>> findByInspectorId(
            @PathVariable UUID inspectorId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                inspectionService.findByInspectorId(authentication.getName(), inspectorId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/session/{parkingSessionId}")
    public ResponseEntity<List<InspectionResponse>> findByParkingSessionId(
            @PathVariable UUID parkingSessionId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                inspectionService.findByParkingSessionId(authentication.getName(), parkingSessionId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/space/{parkingSpaceId}")
    public ResponseEntity<List<InspectionResponse>> findByParkingSpaceId(
            @PathVariable UUID parkingSpaceId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                inspectionService.findByParkingSpaceId(authentication.getName(), parkingSpaceId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<List<InspectionResponse>> findByVehicleId(
            @PathVariable UUID vehicleId, Authentication authentication
    ) {
        return ResponseEntity.ok(
                inspectionService.findByVehicleId(authentication.getName(), vehicleId).stream()
                        .map(this::toResponse).toList()
        );
    }

    @GetMapping("/lookup")
    public ResponseEntity<InspectionSituationResponse> lookup(
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String qrCode,
            @RequestParam(required = false) UUID parkingSpaceId,
            Authentication authentication) {
        return ResponseEntity.ok(inspectionService.lookup(
                authentication.getName(), plate, qrCode, parkingSpaceId));
    }

    private InspectionResponse toResponse(Inspection inspection) {
        return new InspectionResponse(inspection.getId(), inspection.getParkingSessionId(),
                inspection.getParkingSpaceId(), inspection.getVehicleId(), inspection.getObservedPlate(),
                inspection.getObservedAt(), inspection.getResult(), inspection.getNotes(),
                inspection.getLatitude(), inspection.getLongitude(), inspection.getCreatedAt());
    }
}
