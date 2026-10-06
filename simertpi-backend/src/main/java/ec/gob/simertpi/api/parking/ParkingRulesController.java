package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.rules.ParkingRulesResult;
import ec.gob.simertpi.application.parking.rules.ParkingRulesService;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/parking/rules")
public class ParkingRulesController {
    private final ParkingRulesService rules;
    public ParkingRulesController(ParkingRulesService rules) { this.rules = rules; }
    @GetMapping("/options")
    public java.util.List<ParkingRulesResult> options(@RequestParam UUID spaceId) {
        Instant now = Instant.now();
        var policy = rules.evaluate(spaceId, null, now, null);
        return ec.gob.simertpi.application.parking.rules.ParkingDurationOptions.quotes(policy,
                minutes -> rules.evaluate(spaceId, null, now, minutes));
    }
    @GetMapping
    public ParkingRulesResult evaluate(@RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) String qrCode, @RequestParam(required = false) Instant at,
            @RequestParam(required = false) Integer durationMinutes) {
        return rules.evaluate(spaceId, qrCode, at, durationMinutes);
    }
}
