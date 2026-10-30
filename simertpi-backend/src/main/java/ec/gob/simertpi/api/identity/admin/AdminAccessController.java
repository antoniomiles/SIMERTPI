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
    private final ec.gob.simertpi.application.identity.admin.AdminRoleManagementService roleManagement;
    private final ec.gob.simertpi.application.identity.invitation.ActivationInvitationDeliveryService invitationDelivery;

    public AdminAccessController(AdminAccessQueryService access, AdminAccessManagementService management,
                                 ec.gob.simertpi.application.identity.admin.AdminRoleManagementService roleManagement,
                                 ec.gob.simertpi.application.identity.invitation.ActivationInvitationDeliveryService invitationDelivery) {
        this.access = access;
        this.management = management;
        this.roleManagement = roleManagement;
        this.invitationDelivery = invitationDelivery;
    }

    public record Account(UUID id, String username, String email, String firstName, String lastName, String phone,
                          List<String> roles, boolean enabled, boolean activationPending,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record UserPage(List<Account> content, int page, int size, long totalElements, int totalPages) { }
    public record CreateAccountDeliveryResponse(Account account, String deliveryStatus, OffsetDateTime activationExpiresAt) { }

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
    public record ActivationResponse(String deliveryStatus, OffsetDateTime activationExpiresAt) { }

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
    public ResponseEntity<List<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.Role>> roles(
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roleManagement.roles(authentication.getName()));
    }

    @GetMapping("/permissions")
    public ResponseEntity<PermissionMatrix> permissions(org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.permissions(authentication.getName()));
    }

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> permissions) { }
    public record UpdateRoleRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> permissions,
            @NotNull OffsetDateTime updatedAt, @Min(0) long rowVersion,
            @NotBlank @Size(max = 500) String reason) { }
    public record RoleStatusRequest(@NotNull Boolean enabled, @NotNull OffsetDateTime updatedAt,
                                    @Min(0) long rowVersion, @NotBlank @Size(max = 500) String reason) { }

    @GetMapping("/permission-catalog")
    public ResponseEntity<List<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.Permission>> permissionCatalog(
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roleManagement.catalog(authentication.getName()));
    }

    @GetMapping("/roles/{code}")
    public ResponseEntity<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.Role> role(
            @PathVariable String code, org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roleManagement.get(code, authentication.getName()));
    }

    @GetMapping("/roles/{code}/history")
    public ResponseEntity<List<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.History>> roleHistory(
            @PathVariable String code, org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roleManagement.history(code, authentication.getName()));
    }

    @PostMapping("/roles")
    public ResponseEntity<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.Role> createRole(
            @Valid @RequestBody CreateRoleRequest request, org.springframework.security.core.Authentication authentication) {
        var created = roleManagement.create(new ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.CreateRequest(
                request.code(), request.name(), request.description(), request.permissions()), authentication.getName());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(created);
    }

    @PutMapping("/roles/{code}")
    public ResponseEntity<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.Role> updateRole(
            @PathVariable String code, @Valid @RequestBody UpdateRoleRequest request,
            org.springframework.security.core.Authentication authentication) {
        var updated = roleManagement.update(code, new ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.UpdateRequest(
                request.name(), request.description(), request.permissions(), request.updatedAt(), request.rowVersion(), request.reason()), authentication.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(updated);
    }

    @PutMapping("/roles/{code}/status")
    public ResponseEntity<ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.Role> updateRoleStatus(
            @PathVariable String code, @Valid @RequestBody RoleStatusRequest request,
            org.springframework.security.core.Authentication authentication) {
        var updated = roleManagement.setEnabled(code, new ec.gob.simertpi.application.identity.admin.AdminRoleManagementService.StatusRequest(
                request.enabled(), request.updatedAt(), request.rowVersion(), request.reason()), authentication.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(updated);
    }
    @PostMapping("/users")
    public ResponseEntity<CreateAccountDeliveryResponse> create(@Valid @RequestBody CreateAccountRequest request,
            org.springframework.security.core.Authentication authentication) {
        var issued = management.createAccount(request, authentication.getName());
        var delivery = invitationDelivery.deliver(issued.account().id(), issued.activationToken(), issued.activationExpiresAt(), authentication.getName());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new CreateAccountDeliveryResponse(issued.account(), delivery.status(), delivery.expiresAt()));
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
        var delivery = invitationDelivery.deliver(userId, response.activationToken(), response.activationExpiresAt(), authentication.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ActivationResponse(delivery.status(), delivery.expiresAt()));
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
