package ec.gob.simertpi.api.permits;

import ec.gob.simertpi.api.permits.dto.CreatePermitRequest;
import ec.gob.simertpi.api.permits.dto.PermitResponse;
import ec.gob.simertpi.application.permits.PermitService;
import ec.gob.simertpi.domain.permits.entity.Permit;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/permits")
public class PermitController {
    private final PermitService permits;
    private final VehicleRepository vehicles;

    public PermitController(PermitService permits, VehicleRepository vehicles) {
        this.permits = permits;
        this.vehicles = vehicles;
    }

    @PostMapping
    public ResponseEntity<PermitResponse> create(@Valid @RequestBody CreatePermitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(response(permits.create(
                authentication.getName(), authorities(authentication), idempotencyKey, request)));
    }

    @GetMapping("/mine")
    public List<PermitResponse> mine(Authentication authentication) {
        return permits.own(authentication.getName()).stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    public PermitResponse byId(@PathVariable UUID id, Authentication authentication) {
        return response(permits.byId(authentication.getName(), id, authorities(authentication)));
    }

    @GetMapping("/vehicle/plate/{plate}")
    public List<PermitResponse> byPlate(@PathVariable String plate, Authentication authentication) {
        return permits.byPlate(plate, authorities(authentication)).stream().map(this::response).toList();
    }

    @GetMapping("/active")
    public List<PermitResponse> active(Authentication authentication) {
        return permits.active(authorities(authentication)).stream().map(this::response).toList();
    }

    @GetMapping("/expired")
    public List<PermitResponse> expired(Authentication authentication) {
        return permits.expired(authorities(authentication)).stream().map(this::response).toList();
    }

    @DeleteMapping("/{id}")
    public PermitResponse cancel(@PathVariable UUID id, Authentication authentication) {
        return response(permits.cancel(authentication.getName(), id, authorities(authentication)));
    }

    private PermitResponse response(Permit permit) {
        String plate = vehicles.findById(permit.getVehicleId()).map(Vehicle::getPlate).orElse(null);
        return new PermitResponse(permit.getId(), permit.getUserId(), permit.getVehicleId(), plate,
                permit.getZoneId(), permit.getPermitType(), permit.getStatus(), permit.getValidFrom(),
                permit.getValidTo(), permit.getAuthorizationCode(), permit.getNotes(),
                permit.getCreatedAt(), permit.getUpdatedAt());
    }

    private Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream().map(a -> a.getAuthority()).collect(java.util.stream.Collectors.toSet());
    }
}
