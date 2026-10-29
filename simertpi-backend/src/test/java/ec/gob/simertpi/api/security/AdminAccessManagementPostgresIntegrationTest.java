package ec.gob.simertpi.api.security;

import ec.gob.simertpi.api.identity.admin.AdminAccessController.AccountStatusRequest;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.UpdateAccountRequest;
import ec.gob.simertpi.application.identity.admin.AdminAccessManagementService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
class AdminAccessManagementPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminAccessManagementService management;

    private UUID actorId;
    private UUID accountId;
    private String actor;

    @BeforeEach
    void prepare() {
        actor = "access-management-admin-" + UUID.randomUUID();
        actorId = createUser(actor, "IT_ADMIN", true);
        accountId = createUser("access-management-citizen-" + UUID.randomUUID(), "CITIZEN", true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "not-a-credential",
                        List.of(new SimpleGrantedAuthority("IT_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void updatesProfileWithOptimisticVersionAndAuditsTheRealChange() {
        OffsetDateTime before = version(accountId);
        var updated = management.updateAccount(accountId,
                new UpdateAccountRequest("updated@example.invalid", "Nombre", "Apellido", "+593 99", "Corrección solicitada", before), actor);

        assertThat(updated.email()).isEqualTo("updated@example.invalid");
        assertThat(updated.firstName()).isEqualTo("Nombre");
        assertThat(updated.updatedAt()).isAfter(before);
        assertThat(jdbc.queryForObject("SELECT email FROM identity.users WHERE id=?", String.class, accountId))
                .isEqualTo("updated@example.invalid");
        assertThat(auditCount("ADMIN_USER_UPDATED")).isEqualTo(1);
        assertThat(auditMetadata("ADMIN_USER_UPDATED", "reason")).isEqualTo("Corrección solicitada");
        assertThat(auditMetadata("ADMIN_USER_UPDATED", "before.email")).contains("@example.invalid");
    }

    @Test
    void rejectsStaleProfileUpdateWithoutPartialPersistence() {
        OffsetDateTime stale = version(accountId);
        management.updateAccount(accountId,
                new UpdateAccountRequest("first@example.invalid", "First", "Change", null, "Primera actualización", stale), actor);
        assertThatThrownBy(() -> management.updateAccount(accountId,
                new UpdateAccountRequest("second@example.invalid", "Second", "Change", null, "Actualización obsoleta", stale), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cambió desde que fue consultada");
        assertThat(jdbc.queryForObject("SELECT email FROM identity.users WHERE id=?", String.class, accountId))
                .isEqualTo("first@example.invalid");
    }

    @Test
    void updatesStatusAndRefusesSelfDisable() {
        OffsetDateTime before = version(accountId);
        var updated = management.updateStatus(accountId, new AccountStatusRequest(false, "Fin de funciones", before), actor);
        assertThat(updated.enabled()).isFalse();
        assertThat(auditCount("ADMIN_USER_DISABLED")).isEqualTo(1);

        OffsetDateTime actorVersion = version(actorId);
        assertThatThrownBy(() -> management.updateStatus(actorId,
                new AccountStatusRequest(false, "Prueba de autoprotección", actorVersion), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("propia cuenta administrativa");
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, actorId)).isTrue();
    }

    @Test
    void pendingInspectorMustUseTheActivationFlowInsteadOfAnAdministrativeEnableToggle() {
        UUID inspectorId = createUser("access-management-pending-" + UUID.randomUUID(), "INSPECTOR", false);
        assertThatThrownBy(() -> management.updateStatus(inspectorId,
                new AccountStatusRequest(true, "El inspector debe activar su cuenta", version(inspectorId)), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enlace de activación");
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, inspectorId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE id=?", String.class, inspectorId)).isNull();
    }

    @Test
    void concurrentCaseVariantEmailUpdatesCannotCreateDuplicateAccounts() throws Exception {
        UUID first = createUser("access-management-email-a-" + UUID.randomUUID(), "CITIZEN", true);
        UUID second = createUser("access-management-email-b-" + UUID.randomUUID(), "CITIZEN", true);
        OffsetDateTime firstVersion = version(first);
        OffsetDateTime secondVersion = version(second);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> concurrentEmailUpdate(first, firstVersion,
                    "shared@example.invalid", ready, start));
            var two = pool.submit(() -> concurrentEmailUpdate(second, secondVersion,
                    "SHARED@example.invalid", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.users WHERE lower(email)=lower(?)",
                    Integer.class, "shared@example.invalid")).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private boolean concurrentEmailUpdate(UUID id, OffsetDateTime expected, String email,
                                          CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "not-a-credential",
                        List.of(new SimpleGrantedAuthority("IT_ADMIN"))));
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency test was not released");
            management.updateAccount(id, new UpdateAccountRequest(email, "Concurrent", "Update", null,
                    "Correo administrativo duplicado", expected), actor);
            return true;
        } catch (IllegalArgumentException duplicate) {
            return false;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private UUID createUser(String username, String role, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identity.users(id,username,email,first_name,last_name,enabled)
                VALUES (?,?,?,?,?,?)
                """, id, username, username + "@example.invalid", "Test", "Account", enabled);
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?", id, role);
        return id;
    }

    private OffsetDateTime version(UUID id) {
        return jdbc.queryForObject("SELECT updated_at FROM identity.users WHERE id=?", OffsetDateTime.class, id);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_id=? AND action=? AND result='SUCCESS'",
                Integer.class, actorId, action);
    }

    private String auditMetadata(String action, String field) {
        return jdbc.queryForObject("SELECT metadata->>? FROM audit.functional_audit_log WHERE actor_id=? AND action=? AND result='SUCCESS'",
                String.class, field, actorId, action);
    }
}
