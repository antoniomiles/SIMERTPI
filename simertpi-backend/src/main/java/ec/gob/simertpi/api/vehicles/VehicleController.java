package ec.gob.simertpi.api.vehicles;

import ec.gob.simertpi.application.vehicles.VehicleService;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> createVehicle(
            @Valid @RequestBody CreateVehicleRequest request, Authentication authentication) {
        Vehicle vehicle = vehicleService.createVehicle(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(vehicle));
    }

    @GetMapping("/user/{userId}")
    public List<VehicleResponse> findByUserId(@PathVariable UUID userId, Authentication authentication) {
        return vehicleService.findByUserId(userId, authentication.getName()).stream().map(this::toResponse).toList();
    }

    @GetMapping("/plate/{plate}")
    public ResponseEntity<VehicleResponse> findByPlate(@PathVariable String plate, Authentication authentication) {
        return vehicleService.findByPlate(plate, authentication.getName())
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/deactivation")
    public VehicleResponse deactivate(@PathVariable UUID id, Authentication authentication) {
        return toResponse(vehicleService.deactivate(id, authentication.getName()));
    }

    private VehicleResponse toResponse(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(), vehicle.getUserId(), vehicle.getPlate(),
                vehicle.getBrand(), vehicle.getModel(), vehicle.getColor(),
                vehicle.isActive(), vehicle.getCreatedAt(), vehicle.getUpdatedAt()
        );
    }
}
