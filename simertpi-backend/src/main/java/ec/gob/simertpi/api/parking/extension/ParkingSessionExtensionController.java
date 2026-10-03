package ec.gob.simertpi.api.parking.extension;

import ec.gob.simertpi.api.parking.extension.dto.CreateSessionExtensionRequest;
import ec.gob.simertpi.application.parking.extension.ParkingSessionExtensionService;
import ec.gob.simertpi.application.parking.ParkingSessionService;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/v1/parking-sessions")
public class ParkingSessionExtensionController {

    private final ParkingSessionExtensionService extensionService;
    private final ParkingSessionService parkingSessionService;

    public ParkingSessionExtensionController(
            ParkingSessionExtensionService extensionService,
            ParkingSessionService parkingSessionService
    ) {
        this.extensionService = extensionService;
        this.parkingSessionService = parkingSessionService;
    }

    @PostMapping("/{parkingSessionId}/extensions")
    public ResponseEntity<SessionExtension> requestExtension(
            @PathVariable UUID parkingSessionId,
            @Valid @RequestBody CreateSessionExtensionRequest request,
            Authentication authentication
    ) {
        parkingSessionService.assertSessionOwner(parkingSessionId, authentication.getName());
        SessionExtension extension = extensionService.requestExtension(
                parkingSessionId,
                request.additionalMinutes(),
                request.provider(),
                request.paymentMethod(),
                request.idempotencyKey()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(extension);
    }

    @GetMapping("/extensions/{extensionId}")
    public ResponseEntity<SessionExtension> findById(
            @PathVariable UUID extensionId,
            Authentication authentication
    ) {
        SessionExtension extension = extensionService.findById(extensionId);
        parkingSessionService.assertSessionOwner(extension.getParkingSessionId(), authentication.getName());
        return ResponseEntity.ok(extension);
    }

    @GetMapping("/{parkingSessionId}/extensions/{extensionId}")
    public ResponseEntity<SessionExtension> findByIdForSession(
            @PathVariable UUID parkingSessionId,
            @PathVariable UUID extensionId,
            Authentication authentication
    ) {
        SessionExtension extension = extensionService.findById(extensionId);

        if (!extension.getParkingSessionId().equals(parkingSessionId)) {
            return ResponseEntity.notFound().build();
        }

        parkingSessionService.assertSessionOwner(parkingSessionId, authentication.getName());

        return ResponseEntity.ok(extension);
    }
}
