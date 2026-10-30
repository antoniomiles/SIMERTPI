package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSmtpConfigurationMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void migratesV51ToV52AndEnforcesVersionedEncryptedConfiguration() throws Exception {
        var postgres = postgresContainer();
        String database = "qa_smtp_v52_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database); }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        Flyway before = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("51").load();
        before.migrate();
        Flyway after = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        after.validate();
        try (var connection = after.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var query = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
                assertThat(query.next()).isTrue();
                assertThat(query.getString(1)).isEqualTo("52");
            }
            UUID id = UUID.randomUUID();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO identity.admin_smtp_configuration
                      (id,provider,display_name,from_address,smtp_host,smtp_port,security_mode,smtp_username,
                       credential_nonce,credential_ciphertext,state,created_by,updated_by)
                    VALUES (?,'GMAIL','SIMERTPI','x@example.test','smtp.gmail.com',587,'STARTTLS','x@example.test',?,?,?, 'qa','qa')
                    """)) {
                insert.setObject(1, id); insert.setBytes(2, new byte[12]); insert.setBytes(3, new byte[32]); insert.setString(4, "ACTIVE");
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO identity.admin_smtp_configuration
                      (id,provider,display_name,from_address,smtp_host,smtp_port,security_mode,smtp_username,
                       credential_nonce,credential_ciphertext,state,created_by,updated_by)
                    VALUES (?,'GMAIL','SIMERTPI','x@example.test','smtp.gmail.com',587,'STARTTLS','x@example.test',?,?,?, 'qa','qa')
                    """)) {
                insert.setObject(1, UUID.randomUUID()); insert.setBytes(2, new byte[12]); insert.setBytes(3, new byte[32]); insert.setString(4, "ACTIVE");
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class);
            }
            try (var query = statement.executeQuery("SELECT count(*) FROM identity.admin_smtp_test_attempt")) {
                assertThat(query.next()).isTrue(); assertThat(query.getLong(1)).isZero();
            }
        }
    }
}
