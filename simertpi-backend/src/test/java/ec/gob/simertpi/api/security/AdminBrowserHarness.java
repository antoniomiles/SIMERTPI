package ec.gob.simertpi.api.security;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.application.parking.ParkingSessionService;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
/** Test-only real browser server. Always uses the disposable container infrastructure. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=8089","server.address=127.0.0.1","logging.level.root=WARN","spring.jpa.show-sql=false","simertpi.admin-auth.allowed-origin=https://localhost:4443","simertpi.admin-auth.idle-ttl=5m","simertpi.admin-auth.absolute-ttl=15m"})
@EnabledIfSystemProperty(named="simertpi.admin-browser-harness",matches="true")
class AdminBrowserHarness extends AbstractPostgresIntegrationTest {
 @Autowired UserRepository users;@Autowired RoleRepository roles;@Autowired PasswordEncoder encoder;@Autowired JdbcTemplate jdbc;@Autowired ParkingSessionService parkingSessions;
 @Test void serveDisposableFixturesForChrome()throws Exception {
  UUID browserAdminId=null;
  for(String role:List.of("SIMERTPI_ADMIN","CITIZEN","INSPECTOR","DISABLED")){
   User u=new User();String name="qa."+(role.equals("SIMERTPI_ADMIN")?"admin":role.toLowerCase());
   u.setId(UUID.randomUUID());u.setUsername(name);u.setEmail(name+"@example.invalid");u.setFirstName("Browser");u.setLastName("Fixture");u.setEnabled(!role.equals("DISABLED"));u.setCreatedAt(OffsetDateTime.now());u.setUpdatedAt(u.getCreatedAt());u.setPasswordHash(encoder.encode("chrome-isolated-fixture-2026"));u.getRoles().add(roles.findByCode(role.equals("DISABLED")?"SIMERTPI_ADMIN":role).orElseThrow());users.saveAndFlush(u);
   if(role.equals("SIMERTPI_ADMIN"))browserAdminId=u.getId();
  }
  seedParkingFixtures();
  seedMapStatusFixtures(Objects.requireNonNull(browserAdminId));
  // The auth E2E includes a real five-minute idle-expiry test and multi-module browser flows.
  // Keep the test harness alive long enough for the suite while retaining its explicit stop file.
  Path stop=Path.of("target/admin-browser.stop");long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
  Path mapTransition=Path.of("target/admin-browser-map-transition.command");Path mapTransitionAck=Path.of("target/admin-browser-map-transition.ack");Files.deleteIfExists(mapTransition);Files.deleteIfExists(mapTransitionAck);
  while(!Files.exists(stop)&&System.nanoTime()<deadline){applyMapTransitionCommand(mapTransition,mapTransitionAck);Thread.sleep(100);}
  verifyBrowserWritesPersisted();
 }

 private void seedParkingFixtures() {
  UUID generalZone=UUID.randomUUID(), endNowZone=UUID.randomUUID(), endScheduledZone=UUID.randomUUID(), scheduledZone=UUID.randomUUID(), calendarEndZone=UUID.randomUUID();
  jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE),(?,?,?,TRUE),(?,?,?,TRUE),(?,?,?,TRUE),(?,?,?,TRUE)",
    generalZone,"E2E-GENERAL","E2E General",endNowZone,"E2E-END-NOW","E2E Finalización inmediata",
    endScheduledZone,"E2E-END-SCHEDULED","E2E Finalización programada",scheduledZone,"E2E-SCHEDULED","E2E Programada",
    calendarEndZone,"E2E-CALENDAR-END","E2E Calendario a finalizar");
  jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", UUID.randomUUID(),"E2E-CATALOG","E2E Catálogo");
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  insertTariff(UUID.randomUUID(),"E2E-TAR-GENERAL","E2E Tarifa general",null,"E2E-GENERAL-REF",now.minusDays(2),now.plusDays(60));
  insertTariff(UUID.randomUUID(),"E2E-TAR-END-NOW","E2E Tarifa finalización inmediata",endNowZone,"E2E-END-NOW-REF",now.minusDays(2),null);
  insertTariff(UUID.randomUUID(),"E2E-TAR-END-SCHEDULED","E2E Tarifa finalización programada",endScheduledZone,"E2E-END-SCHEDULED-REF",now.minusDays(2),null);
  insertTariff(UUID.randomUUID(),"E2E-TAR-SCHEDULED","E2E Tarifa programada",scheduledZone,"E2E-TAR-SCHEDULED-REF",now.plusDays(15),now.plusDays(19));
  insertTariff(UUID.randomUUID(),"E2E-TAR-CONFLICT","E2E Tarifa de conflicto",scheduledZone,"E2E-TAR-CONFLICT-REF",now.plusDays(20),null);

  LocalDate today=LocalDate.now(ZoneId.of("America/Guayaquil"));
  UUID currentCalendar=UUID.randomUUID(), scheduledCalendar=UUID.randomUUID(), endCalendar=UUID.randomUUID();
  jdbc.update("INSERT INTO parking.billing_day_calendars(id,zone_id,valid_from,normative_reference,active) VALUES (?,?,?,?,TRUE),(?,?,?,?,TRUE),(?,?,?,?,TRUE)",
    currentCalendar,null,today.minusDays(20),"E2E-CAL-GENERAL",scheduledCalendar,scheduledZone,today.plusDays(15),"E2E-CAL-SCHEDULED",
    endCalendar,calendarEndZone,today.minusDays(10),"E2E-CAL-END");
  for(int day=1;day<=7;day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)",currentCalendar,day,day<=5);
  jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?),(?,?,?)",scheduledCalendar,1,true,scheduledCalendar,2,true);
  for(int day=1;day<=7;day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)",endCalendar,day,day<=5);
 }

 private void seedMapStatusFixtures(UUID ownerId) {
  UUID zoneId=UUID.randomUUID(),streetId=UUID.randomUUID(),vehicleId=UUID.randomUUID();
  jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)",zoneId,"E2E-MAP-STATES","E2E map status fixtures");
  jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?, ?,TRUE)",streetId,zoneId,"E2E-MAP-ST","E2E map status fixtures");
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  Long configuredThreshold=jdbc.queryForObject("SELECT max(minutes_before)::bigint*60 FROM configuration.notification_rules WHERE event_type IN ('EXPIRATION','PARKING_ENDING_SOON') AND enabled=true AND minutes_before>0 AND valid_from<=CURRENT_TIMESTAMP AND (valid_to IS NULL OR valid_to>=CURRENT_TIMESTAMP)",Long.class);
  long endingSoonThreshold=configuredThreshold==null?600:configuredThreshold;
  jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,created_at,updated_at) VALUES (?,?,?,?,?)",vehicleId,ownerId,"E2EMAP01",now,now);
  String[] names={"ACTIVE","ENDING","EXPIRED","MAX-TIME","PAYMENT","NO-SESSION","DISABLED","UNKNOWN"};
  String[] states={"ACTIVE","ACTIVE","EXPIRED","MAX_TIME_REACHED","PENDING_PAYMENT",null,"ACTIVE","EXPIRED"};
  for(int i=0;i<names.length;i++) {
   UUID spaceId=UUID.randomUUID();String code="E2E-MAP-"+names[i];boolean enabled=!"DISABLED".equals(names[i]);
   BigDecimal latitude=BigDecimal.valueOf(-3.673d+i*0.0003d).setScale(7,RoundingMode.HALF_UP),longitude=BigDecimal.valueOf(-79.653d+i*0.0003d).setScale(7,RoundingMode.HALF_UP);
   jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,latitude,longitude,space_type,active) VALUES (?,?,?,?,?,?,?,'STANDARD',?)",
       spaceId,streetId,code,"SIMERTPI:SPACE:"+UUID.randomUUID(),String.format("%02d",i+1),latitude,longitude,enabled);
   if(states[i]!=null) {
    OffsetDateTime end=switch(names[i]) { case "ENDING" -> now.plusSeconds(Math.max(1,endingSoonThreshold-30)); case "EXPIRED","MAX-TIME","PAYMENT" -> now.minusMinutes(1); case "UNKNOWN" -> now.plusHours(1); default -> now.plusHours(1); };
    jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status,total_amount,extension_count,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,0,?,?)",
        UUID.randomUUID(),ownerId,vehicleId,spaceId,now.minusHours(1),end,states[i],now,now);
   }
  }
  UUID transitionZone=UUID.randomUUID(),transitionStreet=UUID.randomUUID(),transitionSpace=UUID.randomUUID(),transitionVehicle=UUID.randomUUID(),transitionSession=UUID.randomUUID();
  jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)",transitionZone,"E2E-MAP-TRANSITIONS","E2E map transitions");
  jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?,?,TRUE)",transitionStreet,transitionZone,"E2E-MAP-TRANSITIONS-ST","E2E map transitions");
  jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,latitude,longitude,space_type,active) VALUES (?,?,?,?,?,-3.6700000,-79.6500000,'STANDARD',TRUE)",transitionSpace,transitionStreet,"E2E-MAP-TRANSITIONS-P","SIMERTPI:SPACE:"+transitionSpace,"01");
  jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,created_at,updated_at) VALUES (?,?,?,?,?)",transitionVehicle,ownerId,"E2ETRANS01",now,now);
  UUID tariffId=jdbc.queryForObject("SELECT id FROM parking.tariffs WHERE code='E2E-TAR-GENERAL'",UUID.class);
  jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,tariff_id,started_at,expected_end_at,status,total_amount,extension_count,created_at,updated_at) VALUES (?,?,?,?,?,?,?, 'ACTIVE',0,0,?,?)",transitionSession,ownerId,transitionVehicle,transitionSpace,tariffId,now.minusMinutes(5),now.plusHours(1),now,now);
 }

 private void applyMapTransitionCommand(Path commandFile,Path acknowledgmentFile)throws Exception{
  if(!Files.exists(commandFile))return;
  String command=Files.readString(commandFile).trim();
  UUID sessionId=jdbc.queryForObject("SELECT s.id FROM parking.parking_sessions s JOIN parking.parking_spaces p ON p.id=s.parking_space_id WHERE p.code='E2E-MAP-TRANSITIONS-P'",UUID.class);
  switch(command){
   case "YELLOW" -> {
    Long configured=jdbc.queryForObject("SELECT max(minutes_before)::bigint*60 FROM configuration.notification_rules WHERE event_type IN ('EXPIRATION','PARKING_ENDING_SOON') AND enabled=true AND minutes_before>0 AND valid_from<=CURRENT_TIMESTAMP AND (valid_to IS NULL OR valid_to>=CURRENT_TIMESTAMP)",Long.class);
    long threshold=configured==null?600:configured;
    jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'),updated_at=CURRENT_TIMESTAMP WHERE id=?",Math.max(1,threshold-30),sessionId);
   }
   case "EXPIRED" -> jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=CURRENT_TIMESTAMP-INTERVAL '1 second',updated_at=CURRENT_TIMESTAMP WHERE id=?",sessionId);
   case "DISABLE" -> jdbc.update("UPDATE parking.parking_spaces SET active=FALSE,updated_at=CURRENT_TIMESTAMP WHERE code='E2E-MAP-TRANSITIONS-P'");
   case "COMPLETE" -> {
    jdbc.update("UPDATE parking.parking_spaces SET active=TRUE,updated_at=CURRENT_TIMESTAMP WHERE code='E2E-MAP-TRANSITIONS-P'");
    parkingSessions.closeForUsername(sessionId,"qa.admin");
   }
   default -> throw new IllegalArgumentException("Unknown synthetic map transition command: "+command);
  }
  Files.deleteIfExists(commandFile);
  Files.writeString(acknowledgmentFile,command);
 }

 private void insertTariff(UUID id,String code,String name,UUID zoneId,String reference,OffsetDateTime from,OffsetDateTime to) {
  jdbc.update("INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,valid_to,created_at,updated_at,normative_reference) VALUES (?,?,?,?,'USD','HALF_UP',5,0.25,60,15,240,TRUE,?,?,?,?,?)",
   id,code,name,zoneId,from,to,OffsetDateTime.now(ZoneOffset.UTC),OffsetDateTime.now(ZoneOffset.UTC),reference);
 }

 private void verifyBrowserWritesPersisted() {
  if (Boolean.getBoolean("simertpi.admin-browser-harness.map-only")) {
   verifyMapTransitionsPersisted();
   return;
  }
  if (Boolean.getBoolean("simertpi.admin-browser-harness.catalog-only")) {
   verifyCatalogWritesPersisted();
   return;
  }
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code='E2E-TAR-GENERAL-V2' AND amount=0.30 AND valid_to='2030-12-23T03:06:00Z'::timestamptz",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code='E2E-TAR-END-NOW' AND valid_to IS NOT NULL",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code='E2E-TAR-END-SCHEDULED' AND valid_to IS NOT NULL AND valid_to > CURRENT_TIMESTAMP",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code='E2E-TAR-SCHEDULED' AND amount=0.40 AND cancellation_reason='Cancelación E2E PostgreSQL' AND cancelled_at IS NOT NULL",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code='E2E-TAR-CONFLICT' AND amount=0.25 AND active AND cancelled_at IS NULL",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariff_change_history WHERE reason LIKE 'E2E %'",Integer.class)).isGreaterThanOrEqualTo(4);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE normative_reference='E2E-CAL-GENERAL-V2' AND valid_from > CURRENT_DATE",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_days d JOIN parking.billing_day_calendars c ON c.id=d.calendar_id WHERE c.normative_reference='E2E-CAL-GENERAL-V2' AND d.chargeable",Integer.class)).isEqualTo(2);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE normative_reference='E2E-CAL-SCHEDULED-V2' AND active=FALSE AND cancellation_reason='Cancelación E2E PostgreSQL'",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_history WHERE reason LIKE 'E2E %'",Integer.class)).isGreaterThanOrEqualTo(4);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE normative_reference='E2E-CAL-END' AND valid_to IS NOT NULL AND valid_to >= CURRENT_DATE",Integer.class)).isEqualTo(1);
 }

 private void verifyMapTransitionsPersisted() {
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
    "SELECT count(*) FROM parking.parking_spaces WHERE code='E2E-MAP-TRANSITIONS-P' AND active", Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
    "SELECT count(*) FROM parking.parking_sessions s JOIN parking.parking_spaces p ON p.id=s.parking_space_id WHERE p.code='E2E-MAP-TRANSITIONS-P' AND s.status='COMPLETED' AND s.ended_at IS NOT NULL", Integer.class)).isEqualTo(1);
 }

 private void verifyCatalogWritesPersisted() {
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.zones WHERE code='E2E-CATALOG-V2' AND name='E2E Zona actualizada' AND active",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.streets st JOIN parking.zones z ON z.id=st.zone_id WHERE z.code='E2E-CATALOG-V2' AND st.code='E2E-STREET-V2' AND st.name='E2E Calle actualizada' AND st.active",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_spaces sp JOIN parking.streets st ON st.id=sp.street_id JOIN parking.zones z ON z.id=st.zone_id WHERE z.code='E2E-CATALOG-V2' AND st.code='E2E-STREET-V2' AND sp.code='E2E-SPACE-V2' AND sp.qr_code LIKE 'SIMERTPI:SPACE:%' AND sp.space_number='02' AND sp.active",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_spaces sp JOIN parking.streets st ON st.id=sp.street_id JOIN parking.zones z ON z.id=st.zone_id WHERE z.code='E2E-CATALOG-V2' AND st.code='E2E-STREET-V2' AND sp.code='E2E-SPACE-V2' AND sp.latitude IS NOT NULL AND sp.longitude IS NOT NULL AND (sp.latitude<>0.1 OR sp.longitude<>0.1)",Integer.class)).isEqualTo(1);
  org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE resource_type IN ('PARKING_ZONE','PARKING_STREET','PARKING_SPACE') AND action IN ('CONFIGURATION_ZONE_CREATED','CONFIGURATION_STREET_CREATED','CONFIGURATION_PARKING_SPACE_CREATED','CONFIGURATION_ZONE_UPDATED','CONFIGURATION_STREET_UPDATED','CONFIGURATION_PARKING_SPACE_UPDATED') AND result='SUCCESS'",Integer.class)).isGreaterThanOrEqualTo(6);
 }
}
