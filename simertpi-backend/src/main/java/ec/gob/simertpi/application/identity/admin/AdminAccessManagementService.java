package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.Account;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.AccountStatusRequest;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.UpdateAccountRequest;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.CreateAccountRequest;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.CreateAccountResponse;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.UpdateRolesRequest;
import ec.gob.simertpi.application.identity.InspectorAccountService;
import ec.gob.simertpi.application.identity.InspectorAlreadyExistsException;
import ec.gob.simertpi.application.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Administrative account maintenance. Role definitions and permission policy remain immutable here. */
@Service
public class AdminAccessManagementService {
    private static final String ADMIN_ROLE = "SIMERTPI_ADMIN";
    private static final String IT_ADMIN = "IT_ADMIN";
    private static final String INSPECTOR = "INSPECTOR";
    private static final long ADMINISTRATOR_GUARD_LOCK = 25250342L;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final InspectorAccountService invitations;

    public AdminAccessManagementService(JdbcTemplate jdbc, AuditService audit, InspectorAccountService invitations) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.invitations = invitations;
    }

    @Transactional
    public CreateAccountResponse createAccount(CreateAccountRequest request, String actorUsername) {
        lockAdministratorGuard();
        Set<String> actorRoles = requireAdministrator(actorUsername);
        Set<String> requested = normalizeRoles(request.roles());
        if (!actorRoles.contains(IT_ADMIN) && !requested.equals(Set.of(INSPECTOR))) {
            throw new org.springframework.security.access.AccessDeniedException("El administrador municipal solo puede crear cuentas de inspector.");
        }
        validateRoleSet(requested);
        try {
            var invitation = invitations.createWithRoles(request.username(), request.email(), request.firstName(),
                    request.lastName(), request.phone(), List.copyOf(requested));
            UUID id = invitation.id();
            audit.success("ADMIN_USER_INVITED", "USER", id, null, Map.of(
                    "reason", clean(request.reason()), "roles", String.join(",", requested),
                    "activationExpiresAt", invitation.activationExpiresAt().toString()));
            return new CreateAccountResponse(account(id), invitation.activationToken(), invitation.activationExpiresAt());
        } catch (InspectorAlreadyExistsException duplicate) {
            throw new IllegalArgumentException("El usuario o correo ya pertenece a otra cuenta.");
        }
    }

    @Transactional
    public Account updateAccount(UUID userId, UpdateAccountRequest request, String actorUsername) {
        Set<String> actorRoles = requireAdministrator(actorUsername);
        Snapshot target = lockUser(userId);
        authorizeTarget(actorRoles, target);
        requireVersion(target, request.updatedAt());
        String email = clean(request.email()).toLowerCase(Locale.ROOT);
        lockEmail(email);
        Boolean emailTaken = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity.users WHERE lower(email)=lower(?) AND id<>?)",
                Boolean.class, email, userId);
        if (Boolean.TRUE.equals(emailTaken)) throw new IllegalArgumentException("El correo ya pertenece a otra cuenta.");

        OffsetDateTime updatedAt = touchUser(userId);
        jdbc.update("""
                UPDATE identity.users SET email=?, first_name=?, last_name=?, phone=?, updated_at=? WHERE id=?
                """, email, clean(request.firstName()), clean(request.lastName()), cleanNullable(request.phone()), updatedAt, userId);
        Map<String, Object> details = new HashMap<>();
        details.put("reason", clean(request.reason()));
        details.put("changedFields", "email,firstName,lastName,phone");
        details.put("before.email", target.email());
        details.put("after.email", email);
        details.put("before.firstName", target.firstName());
        details.put("after.firstName", clean(request.firstName()));
        details.put("before.lastName", target.lastName());
        details.put("after.lastName", clean(request.lastName()));
        details.put("before.phone", target.phone());
        details.put("after.phone", cleanNullable(request.phone()));
        audit.success("ADMIN_USER_UPDATED", "USER", userId, null, details);
        return account(userId);
    }

    @Transactional
    public Account updateStatus(UUID userId, AccountStatusRequest request, String actorUsername) {
        lockAdministratorGuard();
        Set<String> actorRoles = requireAdministrator(actorUsername);
        Snapshot target = lockUser(userId);
        authorizeTarget(actorRoles, target);
        requireVersion(target, request.updatedAt());
        boolean enabled = request.enabled();
        if (!enabled && target.username().equalsIgnoreCase(actorUsername)) {
            throw new IllegalArgumentException("No puedes deshabilitar tu propia cuenta administrativa.");
        }
        if (enabled && !target.enabled() && target.passwordAbsent()) {
            throw new IllegalArgumentException("La cuenta sin contraseña debe activarse mediante el enlace de activación.");
        }
        if (!enabled) for (String role : target.roles()) {
            if (isAdministratorRole(role)) ensureAnotherEnabledAdmin(userId, role);
        }
        OffsetDateTime updatedAt = touchUser(userId);
        jdbc.update("UPDATE identity.users SET enabled=?, updated_at=? WHERE id=?", enabled, updatedAt, userId);
        audit.success(enabled ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED", "USER", userId, null,
                Map.of("reason", clean(request.reason()), "before.enabled", target.enabled(), "after.enabled", enabled));
        return account(userId);
    }

    @Transactional
    public Account updateRoles(UUID userId, UpdateRolesRequest request, String actorUsername) {
        lockAdministratorGuard();
        Set<String> actorRoles = requireAdministrator(actorUsername);
        Snapshot target = lockUser(userId);
        requireVersion(target, request.updatedAt());
        authorizeTarget(actorRoles, target);
        if (target.username().equalsIgnoreCase(actorUsername)) {
            throw new IllegalArgumentException("No puedes cambiar tus propios roles administrativos.");
        }
        Set<String> desired = normalizeRoles(request.roles());
        validateRoleSet(desired);
        if (!actorRoles.contains(IT_ADMIN) && !desired.equals(Set.of(INSPECTOR))) {
            throw new org.springframework.security.access.AccessDeniedException("El administrador municipal solo puede asignar el rol INSPECTOR.");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(desired.size(), "?"));
        Integer known = jdbc.queryForObject("SELECT COUNT(*) FROM identity.roles WHERE code IN (" + placeholders + ")",
                Integer.class, desired.toArray());
        if (known == null || known != desired.size()) throw new IllegalArgumentException("Uno o más roles no existen en el catálogo actual.");
        for (String protectedRole : List.of(IT_ADMIN, ADMIN_ROLE)) {
            if (target.enabled() && target.roles().contains(protectedRole) && !desired.contains(protectedRole)) {
                ensureAnotherEnabledAdmin(userId, protectedRole);
            }
        }
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?", userId);
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code IN (" + placeholders + ")",
                concatenate(userId, desired.toArray()));
        touchUser(userId);
        audit.success("ADMIN_USER_ROLES_UPDATED", "USER", userId, null, Map.of(
                "reason", clean(request.reason()), "before.roles", String.join(",", target.roles()),
                "after.roles", String.join(",", desired)));
        return account(userId);
    }

    @Transactional
    public ActivationResponse reissueActivation(UUID userId, ActivationRequest request, String actorUsername) {
        lockAdministratorGuard();
        Set<String> actorRoles = requireAdministrator(actorUsername);
        if (!actorRoles.contains(IT_ADMIN)) throw new org.springframework.security.access.AccessDeniedException("Solo IT_ADMIN puede reemitir invitaciones de cuentas administrativas.");
        Snapshot target = lockUser(userId);
        requireVersion(target, request.updatedAt());
        if (target.enabled() || !target.passwordAbsent() || !target.invitationPending()) {
            throw new IllegalArgumentException("La cuenta no tiene una invitación pendiente que pueda reemitirse.");
        }
        var issued = invitations.reissueInvite(userId);
        touchUser(userId);
        audit.success("ADMIN_USER_ACTIVATION_REISSUED", "USER", userId, null, Map.of(
                "reason", clean(request.reason()), "activationExpiresAt", issued.expiresAt().toString()));
        return new ActivationResponse(issued.activationToken(), issued.expiresAt());
    }

    private Snapshot lockUser(UUID id) {
        List<Snapshot> rows = jdbc.query("""
                SELECT u.id, u.username, u.email, u.first_name, u.last_name, u.phone, u.enabled,
                       u.password_hash IS NULL AS password_absent, u.updated_at,
                       EXISTS(SELECT 1 FROM identity.inspector_activation_tokens it WHERE it.user_id=u.id) AS invitation_pending
                FROM identity.users u WHERE u.id=? FOR UPDATE
                """, (rs, row) -> new Snapshot(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("email"), rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
                rs.getBoolean("enabled"), rs.getBoolean("password_absent"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getBoolean("invitation_pending"), Set.of()), id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("No se encontró la cuenta administrativa.");
        Snapshot target = rows.getFirst();
        List<String> roles = jdbc.query("""
                SELECT r.code FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id
                WHERE ur.user_id=? ORDER BY r.code
                """, (rs, row) -> rs.getString(1), id);
        return new Snapshot(target.id(), target.username(), target.email(), target.firstName(), target.lastName(),
                target.phone(), target.enabled(), target.passwordAbsent(), target.updatedAt(), target.invitationPending(), Set.copyOf(roles));
    }

    private void lockEmail(String normalizedEmail) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(lower(?), 0))", rs -> null, normalizedEmail);
    }

    private void lockAdministratorGuard() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, ADMINISTRATOR_GUARD_LOCK);
    }

    private void obsoleteLockAdminRole() {
        List<UUID> role = jdbc.query("SELECT id FROM identity.roles WHERE code=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), ADMIN_ROLE);
        if (role.isEmpty()) throw new IllegalStateException("El rol administrativo requerido no está configurado.");
    }

    private void ensureAnotherEnabledAdmin(UUID excludedUser, String roleCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM identity.users u
                JOIN identity.user_roles ur ON ur.user_id=u.id
                JOIN identity.roles r ON r.id=ur.role_id
                WHERE u.enabled=TRUE AND r.code=? AND u.id<>?
                """, Integer.class, roleCode, excludedUser);
        if (count == null || count < 1) {
            throw new IllegalArgumentException("La operación dejaría al sistema sin administradores habilitados.");
        }
    }

    private Set<String> requireAdministrator(String username) {
        List<String> actorRoles = jdbc.query("""
                SELECT r.code FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id
                JOIN identity.roles r ON r.id=ur.role_id WHERE u.username=? AND u.enabled=TRUE ORDER BY r.code FOR SHARE OF u
                """, (rs, row) -> rs.getString(1), username);
        if (!actorRoles.contains(IT_ADMIN) && !actorRoles.contains(ADMIN_ROLE)) {
            throw new org.springframework.security.access.AccessDeniedException("Acceso denegado.");
        }
        return Set.copyOf(actorRoles);
    }

    private static boolean isAdministratorRole(String role) { return IT_ADMIN.equals(role) || ADMIN_ROLE.equals(role); }

    private static void validateRoleSet(Set<String> roles) {
        if (roles.isEmpty()) throw new IllegalArgumentException("Debe asignarse al menos un rol existente.");
        if (roles.contains(IT_ADMIN) && roles.contains(ADMIN_ROLE)) {
            throw new IllegalArgumentException("Una cuenta no puede combinar Superadministrador y Administrador.");
        }
    }

    private static Set<String> normalizeRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) throw new IllegalArgumentException("Debe especificar los roles.");
        Set<String> normalized = roleCodes.stream().map(code -> code == null ? "" : code.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (normalized.stream().anyMatch(String::isEmpty) || normalized.size() != roleCodes.size()) {
            throw new IllegalArgumentException("Los roles no pueden estar vacíos ni repetidos.");
        }
        return normalized;
    }

    private static void authorizeTarget(Set<String> actorRoles, Snapshot target) {
        if (actorRoles.contains(IT_ADMIN)) return;
        if (!actorRoles.contains(ADMIN_ROLE) || !target.roles().contains(INSPECTOR)
                || target.roles().stream().anyMatch(AdminAccessManagementService::isAdministratorRole)) {
            throw new org.springframework.security.access.AccessDeniedException("El administrador municipal solo puede gestionar inspectores.");
        }
    }

    private static Object[] concatenate(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    public record ActivationRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
                                    @jakarta.validation.constraints.NotNull OffsetDateTime updatedAt) { }
    public record ActivationResponse(String activationToken, OffsetDateTime activationExpiresAt) {
        @Override public String toString() { return "ActivationResponse[activationToken=redacted]"; }
    }

    private OffsetDateTime touchUser(UUID userId) {
        return jdbc.queryForObject("""
                UPDATE identity.users
                SET updated_at=GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
                WHERE id=? RETURNING updated_at
                """, OffsetDateTime.class, userId);
    }

    private Account account(UUID id) {
        return jdbc.queryForObject("""
                SELECT u.id,u.username,u.email,u.first_name,u.last_name,u.phone,u.enabled,u.created_at,u.updated_at,
                       (NOT u.enabled AND u.password_hash IS NULL AND EXISTS (
                           SELECT 1 FROM identity.inspector_activation_tokens token WHERE token.user_id=u.id)) AS activation_pending,
                       COALESCE(array_agg(DISTINCT r.code ORDER BY r.code)
                           FILTER (WHERE r.code IS NOT NULL), ARRAY[]::varchar[]) AS role_codes
                FROM identity.users u LEFT JOIN identity.user_roles ur ON ur.user_id=u.id
                LEFT JOIN identity.roles r ON r.id=ur.role_id WHERE u.id=? GROUP BY u.id
                """, (rs, row) -> {
            java.sql.Array array = rs.getArray("role_codes");
            Object[] values = array == null ? new Object[0] : (Object[]) array.getArray();
            return new Account(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                    rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
                    java.util.Arrays.stream(values).map(String::valueOf).toList(), rs.getBoolean("enabled"),
                    rs.getBoolean("activation_pending"), rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class));
        }, id);
    }

    private static void requireVersion(Snapshot current, OffsetDateTime expected) {
        if (!current.updatedAt().isEqual(expected)) {
            throw new IllegalArgumentException("La cuenta cambió desde que fue consultada. Actualiza el directorio e inténtalo nuevamente.");
        }
    }

    private static String clean(String value) { return value.trim(); }
    private static String cleanNullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record Snapshot(UUID id, String username, String email, String firstName, String lastName, String phone,
                           boolean enabled, boolean passwordAbsent, OffsetDateTime updatedAt, boolean invitationPending,
                           Set<String> roles) { }
}
