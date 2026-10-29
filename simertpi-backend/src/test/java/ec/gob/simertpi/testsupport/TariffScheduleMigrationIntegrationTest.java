package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TariffScheduleMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void upgradeFromV44PreservesExistingTariffsAndAddsNullableCancellationMetadata() throws Exception {
        var postgres = postgresContainer();
        String database = "qa_tariff_v50_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        Flyway before = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target("44").outOfOrder(false).load();
        before.migrate();
        assertThat(before.info().current().getVersion().toString()).isEqualTo("44");

        UUID tariffId = UUID.randomUUID();
        try (var connection = before.getConfiguration().getDataSource().getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO parking.tariffs(id,code,name,amount,duration_minutes,min_minutes,max_continuous_minutes,
                         active,valid_from,created_at,updated_at,zone_id,currency,rounding_mode,grace_period_minutes,normative_reference)
                     VALUES (?, 'LEGACY-44', 'Tarifa histórica', 0.25, 60, 15, 240, TRUE,
                         TIMESTAMPTZ '2027-01-01 05:00:00+00', TIMESTAMPTZ '2026-10-01 05:00:00+00',
                         TIMESTAMPTZ '2026-10-01 05:00:00+00', NULL, 'USD', 'HALF_UP', 0, 'Ordenanza histórica')
                     """)) {
            statement.setObject(1, tariffId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        Flyway after = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .outOfOrder(false).cleanDisabled(true).load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(6);
        after.validate();
        assertThat(after.info().current().getVersion().toString()).isEqualTo("50");
        try (var connection = after.getConfiguration().getDataSource().getConnection();
             var statement = connection.prepareStatement("""
                     SELECT code,name,amount,currency,valid_from,valid_to,active,normative_reference,
                            cancelled_at,cancelled_by_user_id,cancellation_reason,version_group_id,previous_version_id,row_version
                     FROM parking.tariffs WHERE id=?
                     """)) {
            statement.setObject(1, tariffId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("code")).isEqualTo("LEGACY-44");
                assertThat(rows.getString("name")).isEqualTo("Tarifa histórica");
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo("0.25");
                assertThat(rows.getString("currency")).isEqualTo("USD");
                assertThat(rows.getObject("valid_from")).isNotNull();
                assertThat(rows.getObject("valid_to")).isNull();
                assertThat(rows.getBoolean("active")).isTrue();
                assertThat(rows.getString("normative_reference")).isEqualTo("Ordenanza histórica");
                assertThat(rows.getObject("cancelled_at")).isNull();
                assertThat(rows.getObject("cancelled_by_user_id")).isNull();
                assertThat(rows.getString("cancellation_reason")).isNull();
                assertThat(rows.getObject("version_group_id")).isEqualTo(tariffId);
                assertThat(rows.getObject("previous_version_id")).isNull();
                assertThat(rows.getLong("row_version")).isZero();
                assertThat(rows.next()).isFalse();
            }
        }
        try (var connection = after.getConfiguration().getDataSource().getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM parking.tariff_change_history WHERE tariff_id=? AND action='BASELINE'")) {
            statement.setObject(1, tariffId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }
}
