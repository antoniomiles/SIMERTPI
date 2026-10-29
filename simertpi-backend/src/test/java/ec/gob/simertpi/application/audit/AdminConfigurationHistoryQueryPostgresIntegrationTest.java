package ec.gob.simertpi.application.audit;

import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
class AdminConfigurationHistoryQueryPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminConfigurationHistoryQueryService history;

    @Test
    void returnsOnlyAllowlistedConfigurationSnapshotsFromImmutableAuditEvents() {
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit.functional_audit_log
                  (id, actor_name, actor_role, action, resource_type, resource_id, result, metadata)
                VALUES (?, 'admin-fixture', 'SIMERTPI_ADMIN', 'OPERATIONAL_SCHEDULE_UPDATED',
                        'PARKING_SCHEDULE', ?, 'SUCCESS',
                        CAST('{\"reason\":\"adjustment\",\"before.startTime\":\"08:00\",\"after.startTime\":\"09:00\",\"password\":\"hidden\",\"unknown\":\"hidden\"}' AS jsonb))
                """, eventId, resourceId);

        var event = history.search(50, 0).stream().filter(row -> row.id().equals(eventId)).findFirst().orElseThrow();

        assertThat(event.actorName()).isEqualTo("admin-fixture");
        assertThat(event.resourceId()).isEqualTo(resourceId);
        assertThat(event.details()).containsEntry("reason", "adjustment")
                .containsEntry("before.startTime", "08:00")
                .containsEntry("after.startTime", "09:00")
                .doesNotContainKey("password")
                .doesNotContainKey("unknown");
    }

    @Test
    void doesNotReturnUnrelatedAuditEvents() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit.functional_audit_log
                  (id, actor_name, actor_role, action, resource_type, result, metadata)
                VALUES (?, 'admin-fixture', 'SIMERTPI_ADMIN', 'PAYMENT_APPROVED', 'PAYMENT', 'SUCCESS', '{}')
                """, eventId);

        assertThat(history.search(50, 0)).noneMatch(row -> row.id().equals(eventId));
    }

    @Test
    void exposesOnlyExplicitlyWhitelistedAccountChangesAndInspectorLifecycleEvents() {
        UUID updateId = UUID.randomUUID();
        UUID inspectorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit.functional_audit_log
                  (id, actor_name, actor_role, action, resource_type, resource_id, result, metadata)
                VALUES (?, 'admin-fixture', 'SIMERTPI_ADMIN', 'ADMIN_USER_UPDATED', 'USER', ?, 'SUCCESS',
                        CAST('{\"reason\":\"correction\",\"changedFields\":\"email,phone\",\"before.email\":\"old@example.invalid\",\"after.email\":\"new@example.invalid\",\"before.phone\":null,\"after.phone\":\"0999999999\",\"activationToken\":\"secret\"}' AS jsonb)),
                       (?, 'admin-fixture', 'SIMERTPI_ADMIN', 'INSPECTOR_CREATED', 'USER', ?, 'SUCCESS',
                        CAST('{\"activationToken\":\"secret\",\"password\":\"secret\"}' AS jsonb))
                """, updateId, inspectorId, UUID.randomUUID(), inspectorId);

        var events = history.search(50, 0);
        var updated = events.stream().filter(row -> row.id().equals(updateId)).findFirst().orElseThrow();
        assertThat(updated.details()).containsEntry("reason", "correction")
                .containsEntry("before.email", "old@example.invalid")
                .containsEntry("after.email", "new@example.invalid")
                .containsEntry("after.phone", "0999999999")
                .doesNotContainKey("activationToken");
        var created = events.stream().filter(row -> row.action().equals("INSPECTOR_CREATED")
                && inspectorId.equals(row.resourceId())).findFirst().orElseThrow();
        assertThat(created.details()).isEmpty();
    }

    @Test
    void exposesSafeRoleInvitationAndBootstrapMetadataButNeverActivationSecrets() {
        UUID inviteId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID activationId = UUID.randomUUID();
        UUID bootstrapId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit.functional_audit_log
                  (id, actor_name, actor_role, action, resource_type, resource_id, result, metadata)
                VALUES (?, 'it-admin-fixture', 'IT_ADMIN', 'ADMIN_USER_INVITED', 'USER', ?, 'SUCCESS',
                        CAST('{\"reason\":\"approved invitation\",\"roles\":\"INSPECTOR\",\"activationExpiresAt\":\"2026-10-30T10:00:00Z\",\"activationToken\":\"hidden\",\"tokenHash\":\"hidden\"}' AS jsonb)),
                       (?, 'it-admin-fixture', 'IT_ADMIN', 'ADMIN_USER_ROLES_UPDATED', 'USER', ?, 'SUCCESS',
                        CAST('{\"reason\":\"role change\",\"before.roles\":\"INSPECTOR\",\"after.roles\":\"AUDITOR\",\"secret\":\"hidden\"}' AS jsonb)),
                       (?, 'it-admin-fixture', 'IT_ADMIN', 'ADMIN_USER_ACTIVATION_REISSUED', 'USER', ?, 'SUCCESS',
                        CAST('{\"reason\":\"expired invite\",\"activationExpiresAt\":\"2026-10-30T10:00:00Z\",\"activationToken\":\"hidden\"}' AS jsonb)),
                       (?, 'bootstrap-operator-fixture', 'BOOTSTRAP_OPERATOR', 'ADMIN_BOOTSTRAP', 'USER', ?, 'SUCCESS',
                        CAST('{\"approvalId\":\"approved-fixture\",\"role\":\"IT_ADMIN\",\"password\":\"hidden\"}' AS jsonb)),
                       (?, 'it-admin-fixture', 'IT_ADMIN', 'INVITED_ACCOUNT_ACTIVATED', 'USER', ?, 'SUCCESS',
                        CAST('{\"roles\":\"IT_ADMIN\",\"activationToken\":\"hidden\"}' AS jsonb))
                """, inviteId, UUID.randomUUID(), roleId, UUID.randomUUID(), activationId, UUID.randomUUID(),
                bootstrapId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var events = history.search(100, 0);
        var invited = events.stream().filter(row -> row.id().equals(inviteId)).findFirst().orElseThrow();
        assertThat(invited.details()).containsEntry("reason", "approved invitation")
                .containsEntry("roles", "INSPECTOR")
                .containsEntry("activationExpiresAt", "2026-10-30T10:00:00Z")
                .doesNotContainKey("activationToken").doesNotContainKey("tokenHash");
        var roles = events.stream().filter(row -> row.id().equals(roleId)).findFirst().orElseThrow();
        assertThat(roles.details()).containsEntry("before.roles", "INSPECTOR").containsEntry("after.roles", "AUDITOR")
                .doesNotContainKey("secret");
        var reissued = events.stream().filter(row -> row.id().equals(activationId)).findFirst().orElseThrow();
        assertThat(reissued.details()).containsEntry("reason", "expired invite").doesNotContainKey("activationToken");
        var bootstrap = events.stream().filter(row -> row.id().equals(bootstrapId)).findFirst().orElseThrow();
        assertThat(bootstrap.details()).containsEntry("approvalId", "approved-fixture").containsEntry("role", "IT_ADMIN")
                .doesNotContainKey("password");
        assertThat(events.stream().filter(row -> row.action().equals("INVITED_ACCOUNT_ACTIVATED")))
                .anyMatch(row -> row.details().containsKey("roles"));
    }
}
