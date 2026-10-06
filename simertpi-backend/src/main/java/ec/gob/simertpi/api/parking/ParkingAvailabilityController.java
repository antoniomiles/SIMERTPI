package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.ParkingAvailabilityService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/parking-spaces/availability")
public class ParkingAvailabilityController {
    private final ParkingAvailabilityService availability;
    public ParkingAvailabilityController(ParkingAvailabilityService availability) { this.availability=availability; }
    @GetMapping
    public List<ParkingAvailabilityService.Availability> list() { return availability.list(); }
}
