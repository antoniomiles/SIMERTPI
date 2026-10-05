package ec.gob.simertpi.api.vehicles;

import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class VehiclePlateMigrationTest extends AbstractPostgresIntegrationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void upgradesLegacyRowsOrStopsAtomicallyOnOwnerCollision(boolean collision) {
        var pg = postgresContainer();
        var admin = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        String database = "vehicle_migration_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        try {
            String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
            var dataSource = new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target("30").load().migrate();
            UUID owner = UUID.randomUUID(), other = collision ? owner : UUID.randomUUID();
            jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name) VALUES (?, 'migration-a','a@example.invalid','fixture','A','Fixture')", owner);
            if (!collision) jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name) VALUES (?, 'migration-b','b@example.invalid','fixture','B','Fixture')", other);
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate) VALUES (?,?,'tbe1234')", a, owner);
            jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate) VALUES (?,?,'TBE1234')", b, other);
            var latest = Flyway.configure().dataSource(dataSource).load();
            if (collision) {
                assertThatThrownBy(latest::migrate).hasStackTraceContaining("active associations collide for an owner");
                assertThat(jdbc.queryForObject("SELECT plate FROM identity.vehicles WHERE id=?", String.class, a)).isEqualTo("tbe1234");
                assertThat(latest.info().current().getVersion().toString()).isEqualTo("30");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='uk_vehicles_plate'", Integer.class)).isEqualTo(1);
            } else {
                latest.migrate(); latest.validate();
                assertThat(latest.info().current().getVersion().toString()).isEqualTo("31");
                assertThat(jdbc.queryForList("SELECT plate FROM identity.vehicles", String.class)).containsExactly("TBE1234", "TBE1234");
                assertThat(jdbc.queryForList("SELECT id FROM identity.vehicles", UUID.class)).containsExactlyInAnyOrder(a,b);
                assertThatThrownBy(() -> jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate) VALUES (?,?,'tBe1234')", UUID.randomUUID(), owner))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.vehicles", Integer.class)).isEqualTo(2);
        } finally { admin.execute("DROP DATABASE " + database); } // Only the database created by this test in Testcontainers.
    }
}
