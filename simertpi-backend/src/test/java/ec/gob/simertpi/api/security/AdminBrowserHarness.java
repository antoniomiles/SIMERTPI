package ec.gob.simertpi.api.security;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.*;
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
 @Autowired UserRepository users;@Autowired RoleRepository roles;@Autowired PasswordEncoder encoder;@Autowired JdbcTemplate jdbc;
 @Test void serveDisposableFixturesForChrome()throws Exception {
  for(String role:List.of("SIMERTPI_ADMIN","CITIZEN","INSPECTOR","DISABLED")){
   User u=new User();String name="qa."+(role.equals("SIMERTPI_ADMIN")?"admin":role.toLowerCase());
   u.setId(UUID.randomUUID());u.setUsername(name);u.setEmail(name+"@example.invalid");u.setFirstName("Browser");u.setLastName("Fixture");u.setEnabled(!role.equals("DISABLED"));u.setCreatedAt(OffsetDateTime.now());u.setUpdatedAt(u.getCreatedAt());u.setPasswordHash(encoder.encode("chrome-isolated-fixture-2026"));u.getRoles().add(roles.findByCode(role.equals("DISABLED")?"SIMERTPI_ADMIN":role).orElseThrow());users.saveAndFlush(u);
  }
  seedParkingFixtures();
  Path stop=Path.of("target/admin-browser.stop");long deadline=System.nanoTime()+Duration.ofMinutes(12).toNanos();
  while(!Files.exists(stop)&&System.nanoTime()<deadline)Thread.sleep(100);
  verifyBrowserWritesPersisted();
 }

 private void seedParkingFixtures() {
  UUID generalZone=UUID.randomUUID(), endNowZone=UUID.randomUUID(), endScheduledZone=UUID.randomUUID(), scheduledZone=UUID.randomUUID(), calendarEndZone=UUID.randomUUID();
  jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE),(?,?,?,TRUE),(?,?,?,TRUE),(?,?,?,TRUE),(?,?,?,TRUE)",
    generalZone,"E2E-GENERAL","E2E General",endNowZone,"E2E-END-NOW","E2E Finalización inmediata",
    endScheduledZone,"E2E-END-SCHEDULED","E2E Finalización programada",scheduledZone,"E2E-SCHEDULED","E2E Programada",
    calendarEndZone,"E2E-CALENDAR-END","E2E Calendario a finalizar");
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

 private void insertTariff(UUID id,String code,String name,UUID zoneId,String reference,OffsetDateTime from,OffsetDateTime to) {
  jdbc.update("INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,valid_to,created_at,updated_at,normative_reference) VALUES (?,?,?,?,'USD','HALF_UP',5,0.25,60,15,240,TRUE,?,?,?,?,?)",
   id,code,name,zoneId,from,to,OffsetDateTime.now(ZoneOffset.UTC),OffsetDateTime.now(ZoneOffset.UTC),reference);
 }

 private void verifyBrowserWritesPersisted() {
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
}
