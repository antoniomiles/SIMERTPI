package ec.gob.simertpi.api.identity.admin;

import ec.gob.simertpi.application.identity.admin.AdminAccessQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/access")
@Validated
public class AdminAccessController {
    private final AdminAccessQueryService access;

    public AdminAccessController(AdminAccessQueryService access) {
        this.access = access;
    }

    public record Account(UUID id, String username, String email, String firstName, String lastName,
                          List<String> roles, boolean enabled, boolean activationPending,
                          OffsetDateTime createdAt) { }

    public record UserPage(List<Account> content, int page, int size, long totalElements, int totalPages) { }

    public record RoleSummary(String code, String name, String description, long assignedAccounts) { }

    @GetMapping("/users")
    public ResponseEntity<UserPage> users(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String role,
            @RequestParam(required = false) Boolean enabled) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(access.users(page, size, q, role, enabled));
    }

    @GetMapping("/roles")
    public ResponseEntity<List<RoleSummary>> roles() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.roles());
    }
}
