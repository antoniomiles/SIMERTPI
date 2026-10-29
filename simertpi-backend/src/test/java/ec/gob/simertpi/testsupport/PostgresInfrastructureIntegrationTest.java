package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PostgresInfrastructureIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired DataSource datasource;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;

    @Test
    void usesContainerConnectionAndValidatesEveryMigrationFromEmptyDatabase() throws Exception {
        assertThat(postgresContainer().isRunning()).isTrue();
        try (var connection = datasource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer().getJdbcUrl());
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
            assertThat(connection.getCatalog()).isEqualTo("simertpi_test");
        }
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("50");
        assertThat(flyway.info().applied()).hasSize(49);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void upgradesV41WithoutChangingExistingIdentitiesOrMobileSessions() {
        try (var pg=new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16.15").withDatabaseName("isolated_admin_upgrade").withUsername("isolated").withPassword(UUID.randomUUID().toString())) {
            pg.start();var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            Flyway.configure().dataSource(source).target("41").load().migrate();var fixture=new JdbcTemplate(source);
            UUID id=UUID.randomUUID();fixture.update("INSERT INTO identity.users(id,username,email,first_name,last_name,enabled) VALUES (?,?,?,?,?,true)",id,"upgrade.fixture","upgrade@example.invalid","Upgrade","Fixture");
            UUID session=UUID.randomUUID();fixture.update("INSERT INTO identity.mobile_sessions(id,user_id,access_hash,access_expires_at,refresh_expires_at,authentication_method,audience) VALUES (?,?,?,CURRENT_TIMESTAMP+INTERVAL '15 minutes',CURRENT_TIMESTAMP+INTERVAL '1 day','PASSWORD','CITIZEN')",session,id,"f".repeat(64));
            var latest=Flyway.configure().dataSource(source).load();latest.migrate();latest.validate();
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("50");
            assertThat(fixture.queryForObject("SELECT username FROM identity.users WHERE id=?",String.class,id)).isEqualTo("upgrade.fixture");
            assertThat(fixture.queryForObject("SELECT audience FROM identity.mobile_sessions WHERE id=?",String.class,session)).isEqualTo("CITIZEN");
            assertThat(fixture.queryForObject("SELECT COUNT(*) FROM identity.admin_web_sessions",Integer.class)).isZero();
        }
    }

    @Test
    void persistsQueriesAndEnforcesPostgresConstraints() {
        UUID id = UUID.randomUUID();
        String code = "QA-" + id;
        try {
            jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)", id, code, "QA fixture");
            assertThat(jdbc.queryForObject("SELECT name FROM parking.zones WHERE id = ?", String.class, id))
                    .isEqualTo("QA fixture");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), code, "Duplicate fixture"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM parking.zones WHERE id = ?", id);
        }
    }
}
