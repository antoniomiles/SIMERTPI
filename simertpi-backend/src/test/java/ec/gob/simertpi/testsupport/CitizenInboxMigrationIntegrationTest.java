package ec.gob.simertpi.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CitizenInboxMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void upgradesV32WithExistingChannelRecordsAndPreservesReadsAndRules() {
        var container=postgresContainer();
        var admin=new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(),container.getUsername(),container.getPassword()));
        String database="cp24_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE "+database);
        try {
            String url=container.getJdbcUrl().replace("/simertpi_test", "/"+database);
            var data=new DriverManagerDataSource(url,container.getUsername(),container.getPassword());
            var jdbc=new JdbcTemplate(data);
            Flyway.configure().dataSource(data).target("32").load().migrate();
            UUID owner=UUID.randomUUID(),source=UUID.randomUUID(),rule=UUID.randomUUID();
            jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name) VALUES(?,'migration-fixture','fixture@example.test','test-placeholder','Test','Migration')",owner);
            jdbc.update("INSERT INTO configuration.notification_rules(id,code,event_type,channel,minutes_before,title_template,message_template,valid_from) VALUES(?,'legacy-reminder','EXPIRATION','PUSH',7,'Title','Body','2000-01-01T00:00:00Z')",rule);
            UUID vehicle=UUID.randomUUID(),zone=UUID.randomUUID(),street=UUID.randomUUID(),space=UUID.randomUUID(),session=UUID.randomUUID();
            jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate) VALUES(?,?,'MIG1234')",vehicle,owner);
            jdbc.update("INSERT INTO parking.zones(id,code,name) VALUES(?,'migration-zone','Migration')",zone);
            jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name) VALUES(?,?,'migration-street','Migration')",street,zone);
            jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number) VALUES(?,?,'migration-space','migration-qr','1')",space,street);
            jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status) VALUES(?,?,?,?,'2026-10-06T09:00Z','2026-10-06T11:00Z','EXTENDED')",session,owner,vehicle,space);
            jdbc.update("INSERT INTO parking.parking_control_events(id,parking_session_id,user_id,vehicle_id,parking_space_id,event_type,occurred_at,minutes_overdue) VALUES(?,?,?,?,?,'EXPIRATION','2026-10-06T10:00Z',0)",UUID.randomUUID(),session,owner,vehicle,space);
            for(String channel:java.util.List.of("PUSH","EMAIL","IN_APP"))
                jdbc.update("INSERT INTO notification.notifications(id,user_id,source_event_id,notification_type,channel,title,message,read_at) VALUES(?,?,?,'PAYMENT_APPROVED',?,'Title','Body',CASE WHEN ?='EMAIL' THEN CURRENT_TIMESTAMP ELSE NULL END)",UUID.randomUUID(),owner,source,channel,channel);
            UUID period=ec.gob.simertpi.application.notifications.NotificationEventIds.stable("PARKING_SESSION",session,"EXPIRATION",java.time.OffsetDateTime.parse("2026-10-06T11:00:00Z"));
            jdbc.update("INSERT INTO notification.notifications(id,user_id,source_event_id,notification_type,channel,title,message,reference_type,reference_id,rule_id) VALUES(?,?,?,'EXPIRATION','PUSH','Aviso previo','Aviso previo','PARKING_SESSION',?,?)",UUID.randomUUID(),owner,period,session,rule);
            var flyway=Flyway.configure().dataSource(data).load();flyway.migrate();flyway.validate();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("35");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications",Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items WHERE read_at IS NOT NULL",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT reference_id FROM notification.inbox_items WHERE event_type='PARKING_ENDING_SOON'",UUID.class)).isEqualTo(session);
            assertThat(jdbc.queryForObject("SELECT event_type FROM configuration.notification_rules WHERE id=?",String.class,rule)).isEqualTo("PARKING_ENDING_SOON");
            assertThat(jdbc.queryForObject("SELECT minutes_before FROM configuration.notification_rules WHERE id=?",Integer.class,rule)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_control_events WHERE contract_end_at IS NULL",Integer.class)).isEqualTo(1);
            jdbc.update("INSERT INTO parking.parking_control_events(id,parking_session_id,user_id,vehicle_id,parking_space_id,event_type,occurred_at,minutes_overdue) VALUES(?,?,?,?,?,'EXPIRATION','2026-10-06T11:00Z',0)",UUID.randomUUID(),session,owner,vehicle,space);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_control_events WHERE contract_end_at='2026-10-06T11:00Z'",Integer.class)).isEqualTo(1);
            UUID inAppRule=jdbc.queryForObject("SELECT id FROM configuration.notification_rules WHERE event_type='PARKING_ENDING_SOON' AND channel='IN_APP'",UUID.class);
            jdbc.update("INSERT INTO notification.notifications(id,user_id,source_event_id,notification_type,channel,title,message,reference_type,reference_id,rule_id) VALUES(?,?,?,'PARKING_ENDING_SOON','IN_APP','Aviso previo','Aviso previo','PARKING_SESSION',?,?)",UUID.randomUUID(),owner,period,session,inAppRule);
            flyway.migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items",Integer.class)).isEqualTo(2);
        } finally { admin.execute("DROP DATABASE "+database+" WITH (FORCE)"); }
    }
}
