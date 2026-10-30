package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicRolesV51MigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test
    void upgradesV50WithoutChangingAssignmentsAndSeedsLeastPrivilegeSecretary() throws Exception {
        var postgres = postgresContainer();
        String database = "qa_dynamic_roles_v51_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        String location = migrationLocation();
        Flyway before = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations(location).target("50").outOfOrder(false).load();
        before.migrate();
        UUID userId = UUID.randomUUID();
        UUID adminRoleId;
        UUID systemRoleId;
        try (var connection = before.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id FROM identity.roles WHERE code='SIMERTPI_ADMIN'")) {
                assertThat(rows.next()).isTrue();
                adminRoleId = rows.getObject(1, UUID.class);
            }
            try (var rows = statement.executeQuery("SELECT id FROM identity.roles WHERE code='IT_ADMIN'")) {
                assertThat(rows.next()).isTrue();
                systemRoleId = rows.getObject(1, UUID.class);
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name)
                    VALUES (?, 'v51-preserved-user', 'v51-preserved@example.test', 'test-hash', 'V51', 'Preserved')
                    """)) {
                insert.setObject(1, userId);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO identity.user_roles(user_id,role_id) VALUES (?,?)")) {
                insert.setObject(1, userId);
                insert.setObject(2, adminRoleId);
                insert.executeUpdate();
            }
        }

        Flyway after = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations(location).outOfOrder(false).load();
        // V51 adds dynamic roles/permissions and V52 adds administrator-managed SMTP.
        assertThat(after.migrate().migrationsExecuted).isEqualTo(2);
        after.validate();
        try (var connection = after.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var query = connection.prepareStatement("""
                    SELECT r.is_system, r.enabled, r.portal_enabled, r.row_version, count(ur.user_id)
                      FROM identity.roles r
                      LEFT JOIN identity.user_roles ur ON ur.role_id=r.id
                     WHERE r.id=? GROUP BY r.id""")) {
                query.setObject(1, adminRoleId);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                    assertThat(rows.getBoolean(2)).isTrue();
                    assertThat(rows.getBoolean(3)).isTrue();
                    assertThat(rows.getLong(4)).isZero();
                    assertThat(rows.getLong(5)).isEqualTo(1);
                }
            }
            try (var rows = statement.executeQuery("""
                    SELECT r.code, r.display_name, r.portal_enabled,
                           array_agg(rp.permission_code ORDER BY rp.permission_code) AS grants,
                           (SELECT count(*) FROM identity.user_roles ur WHERE ur.role_id=r.id) AS assigned_users
                      FROM identity.roles r JOIN identity.role_permissions rp ON rp.role_id=r.id
                     WHERE r.code='SECRETARIA' GROUP BY r.id""")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("display_name")).isEqualTo("Secretaría");
                assertThat(rows.getBoolean("portal_enabled")).isTrue();
                assertThat((String[]) rows.getArray("grants").getArray())
                        .containsExactly("dashboard.view", "reports.view");
                assertThat(rows.getLong("assigned_users")).isZero();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO identity.roles(id,code,name,description,display_name,portal_enabled)
                    VALUES (?, 'QA_READ_ONLY', 'QA lectura', 'Rol de prueba', 'QA lectura', TRUE)
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.executeUpdate();
            }
            try (var invalidSystemRole = connection.prepareStatement("""
                    INSERT INTO identity.roles(id,code,name,description,display_name,is_system)
                    VALUES (?, 'QA_FORGED_SYSTEM', 'Forged', 'Must fail', 'Forged', TRUE)
                    """)) {
                invalidSystemRole.setObject(1, UUID.randomUUID());
                assertThatThrownBy(invalidSystemRole::executeUpdate)
                        .hasMessageContaining("System roles cannot be created");
            }
            try (var protectedGrant = connection.prepareStatement("""
                    INSERT INTO identity.role_permissions(role_id, permission_code)
                    VALUES (?, 'dashboard.view')
                    """)) {
                protectedGrant.setObject(1, systemRoleId);
                assertThatThrownBy(protectedGrant::executeUpdate)
                        .hasMessageContaining("System role permissions are immutable");
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE identity.roles SET display_name='changed' WHERE code='IT_ADMIN'
                    """))
                    .hasMessageContaining("System roles are immutable");
        }
        assertThat(after.migrate().migrationsExecuted).isZero();
    }

    private static String migrationLocation() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path modulePath = directory.resolve("src/main/resources/db/migration");
            if (Files.isDirectory(modulePath)) return "filesystem:" + modulePath.toString().replace('\\', '/');
            Path nestedModulePath = directory.resolve("simertpi-backend/src/main/resources/db/migration");
            if (Files.isDirectory(nestedModulePath)) return "filesystem:" + nestedModulePath.toString().replace('\\', '/');
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate backend Flyway migrations for the isolated test.");
    }
}
