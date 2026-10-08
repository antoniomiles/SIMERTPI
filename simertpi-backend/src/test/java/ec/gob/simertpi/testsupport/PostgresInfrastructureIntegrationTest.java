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
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("35");
        assertThat(flyway.info().applied()).hasSize(35);
        assertThat(flyway.info().pending()).isEmpty();
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
