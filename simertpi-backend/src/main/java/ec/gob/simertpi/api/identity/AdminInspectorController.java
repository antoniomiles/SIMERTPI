package ec.gob.simertpi.api.identity;

import ec.gob.simertpi.application.identity.InspectorAccountService;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.InspectorDirectoryProjection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/inspectors")
@Validated
public class AdminInspectorController {
    private final InspectorAccountService inspectors;
    private final ec.gob.simertpi.application.identity.invitation.ActivationInvitationDeliveryService invitationDelivery;

    public AdminInspectorController(InspectorAccountService inspectors,
            ec.gob.simertpi.application.identity.invitation.ActivationInvitationDeliveryService invitationDelivery) {
        this.inspectors = inspectors;
        this.invitationDelivery = invitationDelivery;
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$") String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) @Pattern(regexp = "^[+0-9 ()-]*$") String phone) {
        @Override public String toString() { return "CreateInspectorRequest[redacted]"; }
    }

    public record InspectorView(UUID id, String username, String email, String firstName, String lastName,
                                String phone, String role, boolean enabled, boolean activationPending,
                                OffsetDateTime createdAt) { }
    public record Directory(List<InspectorView> content, int page, int size, long totalElements, int totalPages) { }
    public record Created(UUID id, String username, String email, String firstName, String lastName,
                          String phone, boolean enabled, boolean activationPending, String deliveryStatus, OffsetDateTime activationExpiresAt) { }
    public record Activation(String deliveryStatus, OffsetDateTime expiresAt) { }

    @GetMapping
    public ResponseEntity<Directory> directory(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "25") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size) {
        var result = inspectors.directory(q, enabled, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("username"))));
        List<InspectorView> content = result.getContent().stream().map(AdminInspectorController::view).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Directory(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping
    public ResponseEntity<Created> create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        var created = inspectors.create(request.username(), request.email(), request.firstName(), request.lastName(), request.phone());
        var delivery = invitationDelivery.deliver(created.id(), created.activationToken(), created.activationExpiresAt(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                new Created(created.id(), created.username(), created.email(), created.firstName(), created.lastName(),
                        created.phone(), created.enabled(), created.activationPending(), delivery.status(), delivery.expiresAt()));
    }

    @PostMapping("/{userId}/activation")
    public ResponseEntity<Activation> reissueActivation(@PathVariable UUID userId, Authentication authentication) {
        var issued = inspectors.reissueActivation(userId);
        var delivery = invitationDelivery.deliver(userId, issued.activationToken(), issued.expiresAt(), authentication.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Activation(delivery.status(), delivery.expiresAt()));
    }

    private static InspectorView view(InspectorDirectoryProjection user) {
        return new InspectorView(user.getId(), user.getUsername(), user.getEmail(), user.getFirstName(),
                user.getLastName(), user.getPhone(), "INSPECTOR", user.getEnabled(), user.getActivationPending(), user.getCreatedAt());
    }
}
