package ec.gob.simertpi.api.identity;

import ec.gob.simertpi.application.identity.admin.AdminCredentialRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AdminCredentialRecoveryController.PATH)
public class AdminCredentialRecoveryController {
    public static final String PATH = "/api/v1/admin/auth/recovery";
    public static final String USERNAME = PATH + "/username";
    public static final String PASSWORD = PATH + "/password";
    public static final String RESET = PATH + "/reset";

    private final AdminCredentialRecoveryService recovery;

    public AdminCredentialRecoveryController(AdminCredentialRecoveryService recovery) {
        this.recovery = recovery;
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 255) String email) {
        @Override public String toString() { return "EmailRequest[redacted]"; }
    }

    public record ResetRequest(@NotBlank @Size(min = 43, max = 43) String token,
                               @NotBlank @Size(min = 12, max = 72) String newPassword) {
        @Override public String toString() { return "ResetRequest[redacted]"; }
    }

    @PostMapping("/username")
    public ResponseEntity<Void> username(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        recovery.request(body.email(), request.getRemoteAddr(), false);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        recovery.request(body.email(), request.getRemoteAddr(), true);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        return recovery.reset(body.token(), body.newPassword(), request.getRemoteAddr())
                ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }
}
