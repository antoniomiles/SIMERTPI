package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalCalendarMigrationV50IntegrationTest extends AbstractPostgresIntegrationTest {
    @Test
    void upgradeFromV49AddsVersionFieldsWithoutChangingExistingOperationalConfiguration() throws Exception {
        var postgres = postgresContainer();
        String database = "qa_calendar_v50_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        String migrationLocation = migrationLocation();
        Flyway before = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations(migrationLocation).target("49").outOfOrder(false).load();
        before.migrate();
        assertThat(before.info().current().getVersion().toString()).isEqualTo("49");

        UUID scheduleId = UUID.randomUUID();
        UUID holidayId = UUID.randomUUID();
        try (var connection = before.getConfiguration().getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO parking.schedules(id, zone_id, day_of_week, start_time, end_time, active,
                        created_at, updated_at, valid_from, valid_to)
                    VALUES (?, NULL, 1, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, NULL)
                    """)) {
                statement.setObject(1, scheduleId);
                statement.setObject(2, LocalTime.of(8, 0));
                statement.setObject(3, LocalTime.of(17, 0));
                statement.setObject(4, LocalDate.of(2030, 1, 1));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO parking.holidays(id, holiday_date, name, active, holiday_type, tariffed,
                        valid_from, valid_to, created_at)
                    VALUES (?, ?, 'Excepción de migración', TRUE, 'NATIONAL_HOLIDAY', TRUE, ?, NULL, CURRENT_TIMESTAMP)
                    """)) {
                statement.setObject(1, holidayId);
                statement.setObject(2, LocalDate.of(2030, 1, 1));
                statement.setObject(3, LocalDate.of(2029, 1, 1));
                statement.executeUpdate();
            }
        }

        Flyway after = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations(migrationLocation).outOfOrder(false).load();
        after.migrate();
        assertThat(after.info().current().getVersion().toString()).isEqualTo("50");
        try (var connection = after.getConfiguration().getDataSource().getConnection();
             var statement = connection.prepareStatement("""
                     SELECT s.row_version, s.day_of_week, s.start_time, s.end_time,
                            h.updated_at, h.name, h.holiday_date
                     FROM parking.schedules s CROSS JOIN parking.holidays h
                     WHERE s.id=? AND h.id=?
                     """)) {
            statement.setObject(1, scheduleId);
            statement.setObject(2, holidayId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("row_version")).isZero();
                assertThat(rows.getShort("day_of_week")).isEqualTo((short) 1);
                assertThat(rows.getObject("start_time", LocalTime.class)).isEqualTo(LocalTime.of(8, 0));
                assertThat(rows.getObject("end_time", LocalTime.class)).isEqualTo(LocalTime.of(17, 0));
                assertThat(rows.getObject("updated_at")).isNotNull();
                assertThat(rows.getString("name")).isEqualTo("Excepción de migración");
                assertThat(rows.getObject("holiday_date", LocalDate.class)).isEqualTo(LocalDate.of(2030, 1, 1));
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static String migrationLocation() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path modulePath = directory.resolve("src/main/resources/db/migration");
            if (Files.isDirectory(modulePath)) {
                return "filesystem:" + modulePath.toString().replace('\\', '/');
            }
            Path nestedModulePath = directory.resolve("simertpi-backend/src/main/resources/db/migration");
            if (Files.isDirectory(nestedModulePath)) {
                return "filesystem:" + nestedModulePath.toString().replace('\\', '/');
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate backend Flyway migrations for the isolated test.");
    }
}
