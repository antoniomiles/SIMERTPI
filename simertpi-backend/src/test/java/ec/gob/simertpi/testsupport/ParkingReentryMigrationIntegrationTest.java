package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/** Upgrade the published V35+V37 history without outOfOrder or repair. */
class ParkingReentryMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    private String newDatabase() throws Exception {
        var postgres = postgresContainer();
        String name = "qa_reentry_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
    }

    private Flyway flyway(String url, String target) {
        var postgres = postgresContainer();
        return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).outOfOrder(false).cleanDisabled(true).load();
    }

    private Map<String, Integer> checksums(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .collect(Collectors.toMap(info -> info.getVersion().toString(), info -> info.getChecksum()));
    }

    @Test void upgradeFromV35AndV37AddsOnlyV38AndPreservesExistingChecksums() throws Exception {
        String url = newDatabase();
        var previous = flyway(url, "37");
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(36);
        previous.validate();
        Map<String, Integer> before = checksums(previous);
        assertThat(before).containsKey("37").doesNotContainKey("36");

        var upgrade = flyway(url, "38");
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        assertThat(upgrade.info().current().getVersion().toString()).isEqualTo("38");
        assertThat(checksums(upgrade)).containsAllEntriesOf(before).containsKey("38").doesNotContainKey("36");
        assertThat(upgrade.info().pending()).isEmpty();
        try (var connection = upgrade.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT cooldown_minutes FROM configuration.parking_reentry_settings WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
            statement.executeUpdate("UPDATE configuration.parking_reentry_settings SET cooldown_minutes=30 WHERE id=1");
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        try (var connection = upgrade.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT cooldown_minutes FROM configuration.parking_reentry_settings WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(30);
        }
    }

    @Test void cleanInstallationUsesV38WithFiveMinutesAndSingletonRangeConstraints() throws Exception {
        var installation = flyway(newDatabase(), "38");
        assertThat(installation.migrate().migrationsExecuted).isEqualTo(37);
        installation.validate();
        assertThat(installation.info().applied()).hasSize(37);
        try (var connection = installation.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*), min(cooldown_minutes) FROM configuration.parking_reentry_settings")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(5);
            }
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("UPDATE configuration.parking_reentry_settings SET cooldown_minutes=0 WHERE id=1"));
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("UPDATE configuration.parking_reentry_settings SET cooldown_minutes=1441 WHERE id=1"));
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO configuration.parking_reentry_settings(id,cooldown_minutes,updated_at) VALUES(2,5,CURRENT_TIMESTAMP)"));
        }
    }
}
