package ec.gob.simertpi.api.enforcement;

import ec.gob.simertpi.application.enforcement.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/parking-sessions/{sessionId}/verbal-warnings")
public class VerbalWarningController {
    private final VerbalWarningService service;
    public VerbalWarningController(VerbalWarningService service) { this.service=service; }
    public record Request(@NotBlank @Size(max=VerbalWarningStore.MAX_OBSERVATION) String observation) { }
    @PostMapping
    public VerbalWarningStore.Warning register(@PathVariable UUID sessionId, @Valid @RequestBody Request request,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return service.register(authentication.getName(),sessionId,request.observation(),key);
    }
}
