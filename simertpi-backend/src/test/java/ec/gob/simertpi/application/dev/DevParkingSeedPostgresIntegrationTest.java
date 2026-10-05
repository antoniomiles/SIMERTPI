package ec.gob.simertpi.application.dev;

import com.fasterxml.jackson.databind.*;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "simertpi.dev-seed.enabled=true")
@ActiveProfiles({"dev", "test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevParkingSeedPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired DevParkingSeed seed;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    // Keep wall-clock POST deterministic near midnight without changing DEV settings.
    @org.springframework.test.context.DynamicPropertySource
    static void testClock(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("simertpi.parking.rules.time-zone", () -> java.time.ZoneOffset.ofHours(
                12-java.time.Instant.now().atOffset(java.time.ZoneOffset.UTC).getHour()).getId());
    }
    @Test void bootstrapsSmallDatasetWithUniqueCodesQrAndRealRelations() {
        assertThat(count("parking.zones", "code LIKE 'PIN-DEV-Z%'" )).isEqualTo(3);
        assertThat(count("parking.streets", "code LIKE 'PIN-DEV-ST%'" )).isEqualTo(3);
        assertThat(count("parking.parking_spaces", "code LIKE 'PIN-DEV-%'" )).isEqualTo(9);
        assertThat(jdbc.queryForList("SELECT DISTINCT name FROM parking.streets WHERE code LIKE 'PIN-DEV-ST%'", String.class))
                .containsExactlyInAnyOrder("García Moreno", "Sucre", "Bolívar");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT qr_code) FROM parking.parking_spaces WHERE code LIKE 'PIN-DEV-%'", Long.class)).isEqualTo(9);
        assertThat(count("parking.parking_spaces", "code LIKE 'PIN-DEV-%' AND (latitude IS NOT NULL OR longitude IS NOT NULL)" )).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_spaces p JOIN parking.streets s ON s.id=p.street_id JOIN parking.zones z ON z.id=s.zone_id WHERE p.code LIKE 'PIN-DEV-%' AND z.code LIKE 'PIN-DEV-Z%'",Long.class)).isEqualTo(9);
        assertThat(count("parking.tariffs", "code LIKE 'PIN-DEV-T%' AND currency='USD' AND grace_period_minutes=3 AND rounding_mode='HALF_UP'" )).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.schedules s JOIN parking.zones z ON z.id=s.zone_id WHERE z.code LIKE 'PIN-DEV-Z%'",Long.class)).isEqualTo(21);
    }
    @Test void repeatedAndConcurrentSeedPreserveIdsAndLocalEdits() throws Exception {
        var before=jdbc.queryForList("SELECT id FROM parking.parking_spaces WHERE code LIKE 'PIN-DEV-%' ORDER BY code", UUID.class);
        jdbc.update("UPDATE parking.zones SET description='Manual DEV annotation' WHERE code='PIN-DEV-Z01'");
        seed.run(null);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> seed.run(null)); var b=pool.submit(() -> seed.run(null));a.get();b.get();
        }
        assertThat(jdbc.queryForList("SELECT id FROM parking.parking_spaces WHERE code LIKE 'PIN-DEV-%' ORDER BY code", UUID.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT description FROM parking.zones WHERE code='PIN-DEV-Z01'",String.class)).isEqualTo("Manual DEV annotation");
        assertThat(count("parking.zones", "code LIKE 'PIN-DEV-Z%'" )).isEqualTo(3);
    }
    @Test void citizenUsesCatalogQrVehicleQuoteAndCreatesOnlyPendingPayment()throws Exception {
        String tag=UUID.randomUUID().toString(); String name="seed-"+tag; String password="seed-fixture-only";
        var registered=request(HttpMethod.POST,"/api/v1/users",Map.of("username",name,"email",name+"@example.invalid","password",password,"firstName","DEV","lastName","Fixture"),null,null);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);String user=json.readTree(registered.getBody()).get("id").asText();
        var login=request(HttpMethod.POST,"/api/v1/auth/login",Map.of("username",name,"password",password),null,null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);String access=json.readTree(login.getBody()).get("accessToken").asText();
        var vehicle=request(HttpMethod.POST,"/api/v1/vehicles",Map.of("userId",user,"plate","D"+tag.replace("-", "").substring(0,8)),access,null);
        assertThat(vehicle.getStatusCode()).isEqualTo(HttpStatus.CREATED);String vehicleId=json.readTree(vehicle.getBody()).get("id").asText();
        assertThat(request(HttpMethod.GET,"/api/v1/vehicles/user/"+user,null,access,null).getBody()).contains(vehicleId);
        var zone=json.readTree(http.getForObject("/api/v1/zones/code/PIN-DEV-Z01",String.class));
        var streets=json.readTree(http.getForObject("/api/v1/streets/zone/"+zone.get("id").asText(),String.class));assertThat(streets.size()).isEqualTo(1);
        var space=json.readTree(http.getForObject("/api/v1/parking-spaces/code/PIN-DEV-001",String.class));
        var qr=json.readTree(http.getForObject("/api/v1/parking-spaces/qr/SIMERTPI-DEV-PIN-001",String.class));assertThat(qr.get("id")).isEqualTo(space.get("id"));
        assertThat(json.readTree(http.getForObject("/api/v1/parking-spaces/street/"+streets.get(0).get("id").asText(),String.class)).size()).isEqualTo(3);
        var quoted=request(HttpMethod.GET,"/api/v1/parking/rules?spaceId="+space.get("id").asText()+"&durationMinutes=5",null,access,null);
        assertThat(quoted.getStatusCode()).isEqualTo(HttpStatus.OK);var quote=json.readTree(quoted.getBody());
        assertThat(quote.get("operational").asBoolean()).isTrue();assertThat(quote.get("calculatedAmount").decimalValue()).isEqualByComparingTo("0.10");
        var tariff=json.readTree(http.getForObject("/api/v1/tariffs/code/"+quote.get("applicableTariff").asText(),String.class));
        var body=Map.of("parkingSpaceQrCode",qr.get("qrCode").asText(),"vehicleId",vehicleId,"tariffId",tariff.get("id").asText(),"durationMinutes",5);
        String key="seed-flow-"+tag;
        var created=request(HttpMethod.POST,"/api/v1/parking/sessions",body,access,key);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);var pending=json.readTree(created.getBody());
        assertThat(pending.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        var replay=request(HttpMethod.POST,"/api/v1/parking/sessions",body,access,key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json.readTree(replay.getBody()).get("id")).isEqualTo(pending.get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_sessions WHERE user_id=?",Long.class,UUID.fromString(user))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE parking_session_id=?",Long.class,UUID.fromString(pending.get("id").asText()))).isZero();
        // Only this ephemeral test's rows; never cleanup a remote deployment.
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id=?",UUID.fromString(user));
        jdbc.update("DELETE FROM parking.parking_sessions WHERE id=?",UUID.fromString(pending.get("id").asText()));
    }
    private long count(String table,String filter){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+filter,Long.class);}
    private ResponseEntity<String> request(HttpMethod method,String path,Object body,String token,String key){
        HttpHeaders headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);if(key!=null)headers.set("Idempotency-Key",key);
        return http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
    }
    @AfterAll void removeDatasetFromSharedTestContainer() {
        jdbc.update("DELETE FROM parking.parking_sessions WHERE parking_space_id IN (SELECT id FROM parking.parking_spaces WHERE code LIKE 'PIN-DEV-%')");
        jdbc.update("DELETE FROM parking.schedules WHERE zone_id IN (SELECT id FROM parking.zones WHERE code LIKE 'PIN-DEV-Z%')");
        jdbc.update("DELETE FROM parking.tariffs WHERE code LIKE 'PIN-DEV-T%'");
        jdbc.update("DELETE FROM parking.parking_spaces WHERE code LIKE 'PIN-DEV-%'");
        jdbc.update("DELETE FROM parking.streets WHERE code LIKE 'PIN-DEV-ST%'");
        jdbc.update("DELETE FROM parking.zones WHERE code LIKE 'PIN-DEV-Z%'");
    }
}
