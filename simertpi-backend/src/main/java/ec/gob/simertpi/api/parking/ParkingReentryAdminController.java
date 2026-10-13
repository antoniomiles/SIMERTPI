package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.ParkingReentryConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/v1/admin/parking/reentry-configuration")
public class ParkingReentryAdminController {
    public record UpdateRequest(@Min(1) @Max(1440) int cooldownMinutes) { }
    public record Response(int cooldownMinutes, java.time.OffsetDateTime updatedAt) { }

    private final ParkingReentryConfigurationService service;

    public ParkingReentryAdminController(ParkingReentryConfigurationService service) { this.service = service; }

    @GetMapping
    public Response current() {
        var settings = service.current();
        return new Response(settings.cooldownMinutes(), settings.updatedAt());
    }

    @PutMapping
    public Response update(@Valid @RequestBody UpdateRequest request, Authentication authentication) {
        var settings = service.update(authentication.getName(), request.cooldownMinutes());
        return new Response(settings.cooldownMinutes(), settings.updatedAt());
    }
}
