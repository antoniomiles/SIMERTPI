package ec.gob.simertpi.api.identity.admin;

import ec.gob.simertpi.application.identity.admin.AdminAccessQueryService;
import ec.gob.simertpi.application.identity.admin.AdminAccessManagementService;
import ec.gob.simertpi.api.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/admin/access")
@Validated
public class AdminAccessController {
    private final AdminAccessQueryService access;
    private final AdminAccessManagementService management;

    public AdminAccessController(AdminAccessQueryService access, AdminAccessManagementService management) {
        this.access = access;
        this.management = management;
    }

    public record Account(UUID id, String username, String email, String firstName, String lastName, String phone,
                          List<String> roles, boolean enabled, boolean activationPending,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record UserPage(List<Account> content, int page, int size, long totalElements, int totalPages) { }

    public record RoleSummary(String code, String name, String displayName, String description, long assignedAccounts) { }
    public record RolePermission(String roleCode, String roleName, List<String> permissions) { }
    public record PermissionMatrix(List<RolePermission> roles) { }
    public record CreateAccountRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$") String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) @Pattern(regexp = "^[+0-9 ()-]*$") String phone,
            @NotNull @Size(min = 1, max = 8) List<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String> roles,
            @NotBlank @Size(max = 500) String reason) {
        @Override public String toString() { return "CreateAccountRequest[username=" + username + ", roles=" + roles + ", activation=redacted]"; }
    }
    public record CreateAccountResponse(Account account, String activationToken, OffsetDateTime activationExpiresAt) {
        @Override public String toString() { return "CreateAccountResponse[account=" + account + ", activationToken=redacted]"; }
    }
    public record UpdateRolesRequest(@NotNull @Size(min = 1, max = 8)
                                     List<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String> roles,
                                     @NotBlank @Size(max = 500) String reason,
                                     @NotNull OffsetDateTime updatedAt) {
        @Override public String toString() { return "UpdateRolesRequest[roles=" + roles + ", reason=redacted]"; }
    }
    public record ActivationResponse(String activationToken, OffsetDateTime activationExpiresAt) {
        @Override public String toString() { return "ActivationResponse[activationToken=redacted]"; }
    }

    public record UpdateAccountRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) @Pattern(regexp = "^[+0-9 ()-]*$") String phone,
            @NotBlank @Size(max = 500) String reason,
            @NotNull OffsetDateTime updatedAt) { }

    public record AccountStatusRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 500) String reason,
                                       @NotNull OffsetDateTime updatedAt) { }

    @GetMapping("/users")
    public ResponseEntity<UserPage> users(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String role,
            @RequestParam(required = false) Boolean enabled,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(access.users(page, size, q, role, enabled, authentication.getName()));
    }

    @GetMapping("/roles")
    public ResponseEntity<List<RoleSummary>> roles(org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.roles(authentication.getName()));
    }

    @GetMapping("/permissions")
    public ResponseEntity<PermissionMatrix> permissions(org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.permissions(authentication.getName()));
    }

    @PostMapping("/users")
    public ResponseEntity<CreateAccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(management.createAccount(request, authentication.getName()));
    }

    @PutMapping("/users/{userId}/roles")
    public ResponseEntity<Account> updateRoles(@PathVariable UUID userId, @Valid @RequestBody UpdateRolesRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(management.updateRoles(userId, request, authentication.getName()));
    }

    @PostMapping("/users/{userId}/activation")
    public ResponseEntity<ActivationResponse> reissueActivation(@PathVariable UUID userId,
            @Valid @RequestBody AdminAccessManagementService.ActivationRequest request,
            org.springframework.security.core.Authentication authentication) {
        var response = management.reissueActivation(userId, request, authentication.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ActivationResponse(response.activationToken(), response.activationExpiresAt()));
    }

    @PutMapping("/users/{userId}")
    public ResponseEntity<Account> updateAccount(@PathVariable UUID userId,
                                                 @Valid @RequestBody UpdateAccountRequest request,
                                                 org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(management.updateAccount(userId, request, authentication.getName()));
    }

    @PutMapping("/users/{userId}/status")
    public ResponseEntity<Account> updateStatus(@PathVariable UUID userId,
                                                @Valid @RequestBody AccountStatusRequest request,
                                                org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(management.updateStatus(userId, request, authentication.getName()));
    }

}
