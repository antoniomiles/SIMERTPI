package ec.gob.simertpi.testsupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;
class PushReminderMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void upgradeV38PreservesHistoryAndAddsExactlyOneOptionalReminder() throws Exception {
        var pg=postgresContainer();
        String database="qa_push_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var s=c.createStatement()) {
            s.execute("CREATE DATABASE "+database);
        }
        String url="jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/"+database;
        var before=Flyway.configure().dataSource(url,pg.getUsername(),pg.getPassword()).target("38").load();
        assertThat(before.migrate().migrationsExecuted).isEqualTo(37);
        var checksums=Arrays.stream(before.info().applied()).collect(Collectors.toMap(i->i.getVersion().toString(),i->i.getChecksum()));
        var after=Flyway.configure().dataSource(url,pg.getUsername(),pg.getPassword()).outOfOrder(false).load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(7);after.validate();
        assertThat(Arrays.stream(after.info().applied()).collect(Collectors.toMap(i->i.getVersion().toString(),i->i.getChecksum())))
            .containsAllEntriesOf(checksums).containsKey("39").doesNotContainKey("36");
        assertThat(after.migrate().migrationsExecuted).isZero();
        try(var c=after.getConfiguration().getDataSource().getConnection();var s=c.createStatement();
            var rows=s.executeQuery("SELECT count(*),bool_and(enabled),bool_or(mandatory) FROM configuration.notification_rules WHERE event_type='PARKING_ENDING_SOON' AND channel='PUSH'")) {
            rows.next();assertThat(rows.getInt(1)).isEqualTo(1);assertThat(rows.getBoolean(2)).isTrue();assertThat(rows.getBoolean(3)).isFalse();
        }
    }
}
