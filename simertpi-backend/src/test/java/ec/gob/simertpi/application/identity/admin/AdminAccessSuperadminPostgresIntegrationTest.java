package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.api.identity.admin.AdminAccessController;
import ec.gob.simertpi.application.identity.InspectorAccountService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
class AdminAccessSuperadminPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminAccessManagementService management;
    @Autowired AdminAccessQueryService queries;
    @Autowired InspectorAccountService invitations;

    private UUID itAdminId;
    private String actor;

    @BeforeEach
    void prepareItAdmin() {
        actor = "it-admin-" + UUID.randomUUID();
        itAdminId = create(actor, "IT_ADMIN");
        authenticate(actor, "IT_ADMIN");
    }

    @AfterEach
    void clearContext() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test
    void invitationUsesExistingSingleUseHashedActivationAndReturnsRedactedToken() {
        var response = management.createAccount(new AdminAccessController.CreateAccountRequest(
                "invited-" + UUID.randomUUID(), "invite-" + UUID.randomUUID() + "@example.invalid",
                "Test", "Inspector", null, List.of("INSPECTOR"), "Alta autorizada"), actor);
        UUID id = response.account().id();
        String token = response.activationToken();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.toString()).doesNotContain(token);
        assertThat(response.account().enabled()).isFalse();
        assertThat(response.account().activationPending()).isTrue();
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM identity.inspector_activation_tokens WHERE user_id=?", String.class, id);
        assertThat(storedHash).hasSize(64).isNotEqualTo(token);
        invitations.activate(token, "a-long-isolated-password-2026");
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.inspector_activation_tokens WHERE user_id=?", Integer.class, id)).isZero();
        assertThatThrownBy(() -> invitations.activate(token, "a-long-isolated-password-2026"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE resource_id=? AND action='ADMIN_USER_INVITED' AND result='SUCCESS'", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit.functional_audit_log WHERE resource_id=? AND action='ADMIN_USER_INVITED'", String.class, id))
                .doesNotContain(token, storedHash);
    }

    @Test
    void onlyItAdminCanReissuePendingInvitationAndRotationInvalidatesPriorToken() {
        var invited = management.createAccount(new AdminAccessController.CreateAccountRequest(
                "pending-" + UUID.randomUUID(), "pending-" + UUID.randomUUID() + "@example.invalid",
                "Test", "Account", null, List.of("IT_ADMIN"), "Invitación inicial"), actor);
        UUID id = invited.account().id();
        var reissued = management.reissueActivation(id,
                new AdminAccessManagementService.ActivationRequest("Reemitir invitación", invited.account().updatedAt()), actor);
        assertThat(reissued.toString()).doesNotContain(reissued.activationToken());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity.inspector_activation_tokens WHERE user_id=?", String.class, id))
                .isNotEqualTo(invited.activationToken());
        assertThatThrownBy(() -> invitations.activate(invited.activationToken(), "a-long-isolated-password-2026"))
                .isInstanceOf(RuntimeException.class);
        invitations.activate(reissued.activationToken(), "a-long-isolated-password-2026");
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE resource_id=? AND action='ADMIN_USER_ACTIVATION_REISSUED' AND result='SUCCESS'", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit.functional_audit_log WHERE resource_id=? AND action='ADMIN_USER_ACTIVATION_REISSUED'", String.class, id))
                .doesNotContain(invited.activationToken(), reissued.activationToken());
        UUID pending = create("pending-forbidden-" + UUID.randomUUID(), "INSPECTOR");
        jdbc.update("INSERT INTO identity.inspector_activation_tokens(user_id,token_hash,expires_at) VALUES (?,repeat('a',64),CURRENT_TIMESTAMP + INTERVAL '1 hour')", pending);
        UUID simAdminId = create("reissuer-denied-" + UUID.randomUUID(), "SIMERTPI_ADMIN");
        assertThatThrownBy(() -> management.reissueActivation(pending,
                new AdminAccessManagementService.ActivationRequest("No autorizado", version(pending)), username(simAdminId)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void itAdminCanAssignExistingRolesButCannotCombineAdministrativeRolesOrEditOwnRoles() {
        UUID target = create("role-target-" + UUID.randomUUID(), "INSPECTOR");
        OffsetDateTime version = version(target);
        var changed = management.updateRoles(target,
                new AdminAccessController.UpdateRolesRequest(List.of("AUDITOR"), "Cambio autorizado", version), actor);
        assertThat(changed.roles()).containsExactly("AUDITOR");
        assertThatThrownBy(() -> management.updateRoles(target,
                new AdminAccessController.UpdateRolesRequest(List.of("IT_ADMIN", "SIMERTPI_ADMIN"), "Invalid", changed.updatedAt()), actor))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no puede combinar");
        assertThatThrownBy(() -> management.updateRoles(itAdminId,
                new AdminAccessController.UpdateRolesRequest(List.of("IT_ADMIN"), "Self", version(itAdminId)), actor))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("propios roles");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='AUDITOR'", Integer.class, target)).isEqualTo(1);
        assertThat(queries.permissions(actor).roles()).anySatisfy(role -> {
            if (role.roleCode().equals("IT_ADMIN")) {
                assertThat(role.roleName()).isEqualTo("Superadministrador");
                assertThat(role.permissions()).contains("USERS_MANAGE_ALL", "ROLES_ASSIGN");
            }
        });
    }

    @Test
    @Transactional
    void itAdminCannotRemoveTheLastEnabledMunicipalAdministrator() {
        UUID municipalAdmin = create("last-sim-admin-" + UUID.randomUUID(), "SIMERTPI_ADMIN");
        jdbc.update("UPDATE identity.users u SET enabled=FALSE WHERE u.id<>? AND EXISTS (SELECT 1 FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='SIMERTPI_ADMIN')", municipalAdmin);
        assertThatThrownBy(() -> management.updateRoles(municipalAdmin,
                new AdminAccessController.UpdateRolesRequest(List.of("INSPECTOR"), "Quitar único admin municipal", version(municipalAdmin)), actor))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sin administradores habilitados");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='SIMERTPI_ADMIN'", Integer.class, municipalAdmin)).isEqualTo(1);
    }

    @Test
    void lastItAdminCannotDisableItselfAndNoPartialStatusChangeOccurs() {
        assertThatThrownBy(() -> management.updateStatus(itAdminId,
                new AdminAccessController.AccountStatusRequest(false, "Deshabilitar último superadmin", version(itAdminId)), actor))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("propia cuenta");
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, itAdminId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE resource_id=? AND action='ADMIN_USER_DISABLED' AND result='SUCCESS'", Integer.class, itAdminId)).isZero();
    }

    @Test
    void concurrentRoleUpdatesRejectTheRequestWithTheStaleVersion() throws Exception {
        UUID target = create("roles-race-" + UUID.randomUUID(), "INSPECTOR");
        OffsetDateTime stale = version(target);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> concurrentRoleUpdate(target, stale, "AUDITOR", ready, start));
            var second = pool.submit(() -> concurrentRoleUpdate(target, stale, "SUPERVISOR", ready, start));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles WHERE user_id=?", Integer.class, target)).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void concurrentRoleChangesUseUpdatedAtAndOnlyOneRequestWins() throws Exception {
        UUID target = create("role-race-" + UUID.randomUUID(), "INSPECTOR");
        OffsetDateTime stale = version(target);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> changeRoleConcurrently(target, stale, "AUDITOR", ready, start));
            var second = pool.submit(() -> changeRoleConcurrently(target, stale, "SUPERVISOR", ready, start));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=?", Integer.class, target)).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void municipalAdminDirectoryContainsOnlyNonAdministrativeInspectors() {
        UUID municipalId = create("sim-admin-" + UUID.randomUUID(), "SIMERTPI_ADMIN");
        String simActor = username(municipalId);
        authenticate(simActor, "SIMERTPI_ADMIN");
        UUID visible = create("visible-inspector-" + UUID.randomUUID(), "INSPECTOR");
        UUID hiddenAdminInspector = create("hidden-dual-" + UUID.randomUUID(), "INSPECTOR", "SIMERTPI_ADMIN");
        UUID hiddenItAdmin = create("hidden-it-" + UUID.randomUUID(), "IT_ADMIN");
        var page = queries.users(0, 100, null, null, null, simActor);
        assertThat(page.content()).extracting(AdminAccessController.Account::id).contains(visible)
                .doesNotContain(hiddenAdminInspector, hiddenItAdmin, itAdminId, municipalId);
        assertThatThrownBy(() -> queries.roles(simActor)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.permissions(simActor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void serviceRejectsNonAdministratorEvenWhenCalledWithoutHttpSecurity() {
        UUID citizen = create("citizen-" + UUID.randomUUID(), "CITIZEN");
        String citizenName = username(citizen);
        assertThatThrownBy(() -> management.updateStatus(itAdminId,
                new AdminAccessController.AccountStatusRequest(false, "No autorizado", version(itAdminId)), citizenName))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.users(0, 10, null, null, null, citizenName))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UUID create(String username, String role, String... extraRoles) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,first_name,last_name,enabled) VALUES (?,?,?,'QA','Account',true)",
                id, username, username + "@example.invalid");
        for (String code : java.util.stream.Stream.concat(java.util.stream.Stream.of(role), java.util.Arrays.stream(extraRoles)).toList()) {
            jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?", id, code);
        }
        return id;
    }

    private OffsetDateTime version(UUID id) {
        return jdbc.queryForObject("SELECT updated_at FROM identity.users WHERE id=?", OffsetDateTime.class, id);
    }
    private String username(UUID id) { return jdbc.queryForObject("SELECT username FROM identity.users WHERE id=?", String.class, id); }
    private void authenticate(String user, String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "fixture-only", List.of(new SimpleGrantedAuthority(role))));
    }

    private boolean changeRoleConcurrently(UUID target, OffsetDateTime stale, String desired,
            java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch start) throws InterruptedException {
        authenticate(actor, "IT_ADMIN");
        ready.countDown();
        try {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency test was not released");
            management.updateRoles(target, new AdminAccessController.UpdateRolesRequest(List.of(desired), "Concurrent role change", stale), actor);
            return true;
        } catch (IllegalArgumentException staleOrRejected) { return false; }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    private boolean concurrentRoleUpdate(UUID target, OffsetDateTime stale, String desired,
            java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch start) throws InterruptedException {
        authenticate(actor, "IT_ADMIN");
        ready.countDown();
        try {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency test was not released");
            management.updateRoles(target, new AdminAccessController.UpdateRolesRequest(List.of(desired), "Concurrent change", stale), actor);
            return true;
        } catch (IllegalArgumentException staleVersion) { return false; }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
}
