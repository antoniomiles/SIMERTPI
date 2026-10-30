package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParkingSpaceCoordinateMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void v48PreservesValidCoordinatesAndAllowsUngeoreferencedSpaces() throws Exception {
        var pg=postgresContainer();
        String database=createDatabase(pg);
        try {
            String url=databaseUrl(pg,database);
            Flyway before=Flyway.configure().dataSource(url,pg.getUsername(),pg.getPassword()).target("47").load();
            before.migrate();
            UUID zone=UUID.randomUUID(),street=UUID.randomUUID(),located=UUID.randomUUID(),pending=UUID.randomUUID();
            try(var connection=before.getConfiguration().getDataSource().getConnection();var statement=connection.createStatement()) {
                statement.execute("INSERT INTO parking.zones(id,code,name) VALUES ('"+zone+"','COORD-MIG-Z','Migration QA')");
                statement.execute("INSERT INTO parking.streets(id,zone_id,code,name) VALUES ('"+street+"','"+zone+"','COORD-MIG-S','Migration QA')");
                statement.execute("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,latitude,longitude) VALUES ('"+located+"','"+street+"','COORD-MIG-P1','COORD-MIG-QR1','1',-3.6812345,-79.6812345)");
                statement.execute("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number) VALUES ('"+pending+"','"+street+"','COORD-MIG-P2','COORD-MIG-QR2','2')");
            }
            Flyway after=Flyway.configure().dataSource(url,pg.getUsername(),pg.getPassword()).load();
            after.migrate();after.validate();
            try(var connection=after.getConfiguration().getDataSource().getConnection();var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT latitude,longitude FROM parking.parking_spaces WHERE id='"+located+"'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal("latitude")).isEqualByComparingTo("-3.6812345");
                assertThat(rows.getBigDecimal("longitude")).isEqualByComparingTo("-79.6812345");
            }
            assertThat(after.info().current().getVersion().toString()).isEqualTo("53");
        } finally { dropDatabase(pg,database); }
    }

    @Test
    void v48RejectsPreexistingDuplicateCoordinatePairsWithoutChangingThem() throws Exception {
        var pg=postgresContainer();
        String database=createDatabase(pg);
        try {
            String url=databaseUrl(pg,database);
            Flyway before=Flyway.configure().dataSource(url,pg.getUsername(),pg.getPassword()).target("47").load();
            before.migrate();
            UUID zone=UUID.randomUUID(),street=UUID.randomUUID();
            try(var connection=before.getConfiguration().getDataSource().getConnection();var statement=connection.createStatement()) {
                statement.execute("INSERT INTO parking.zones(id,code,name) VALUES ('"+zone+"','COORD-DUP-Z','Migration QA')");
                statement.execute("INSERT INTO parking.streets(id,zone_id,code,name) VALUES ('"+street+"','"+zone+"','COORD-DUP-S','Migration QA')");
                statement.execute("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,latitude,longitude) VALUES "
                        +"('"+UUID.randomUUID()+"','"+street+"','COORD-DUP-P1','COORD-DUP-QR1','1',-3.68,-79.68),"
                        +"('"+UUID.randomUUID()+"','"+street+"','COORD-DUP-P2','COORD-DUP-QR2','2',-3.6800000,-79.6800000)");
            }
            Flyway after=Flyway.configure().dataSource(url,pg.getUsername(),pg.getPassword()).load();
            assertThatThrownBy(after::migrate).isInstanceOf(FlywayException.class);
            try(var connection=after.getConfiguration().getDataSource().getConnection();var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT count(*),(SELECT to_regclass('parking.uq_parking_spaces_coordinates') IS NULL) FROM parking.parking_spaces WHERE latitude=-3.68 AND longitude=-79.68")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(2);assertThat(rows.getBoolean(2)).isTrue();
            }
        } finally { dropDatabase(pg,database); }
    }

    private String createDatabase(org.testcontainers.containers.PostgreSQLContainer<?> pg) throws Exception {
        String database="qa_coord_v48_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+database);
        }
        return database;
    }

    private String databaseUrl(org.testcontainers.containers.PostgreSQLContainer<?> pg,String database) {
        return "jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/"+database;
    }

    private void dropDatabase(org.testcontainers.containers.PostgreSQLContainer<?> pg,String database) throws Exception {
        try(var connection=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var statement=connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS "+database+" WITH (FORCE)");
        }
    }
}
