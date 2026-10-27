package ec.gob.simertpi.api.security;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminParkingConfigurationPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-zone-admin-password-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private UUID adminId;
    private String username;
    private Cookie sessionCookie;
    private Cookie csrfCookie;

    @BeforeEach
    void createAdministratorSession() throws Exception {
        username = "zones-admin-" + UUID.randomUUID();
        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                adminId, username, username + "@example.invalid", encoder.encode(PASSWORD), "Zone", "Admin");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", adminId);

        MvcResult csrf = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        csrfCookie = cookie(csrf, "__Host-SIMERTPI_ADMIN_XSRF");
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        sessionCookie = cookie(login, AdminAuthenticationService.COOKIE);
    }

    @Test
    void administrativeCatalogRequiresAdminSessionAndLeavesExistingPublicReadContractIntact() throws Exception {
        mvc.perform(get("/api/v1/admin/parking/zones").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/parking/zones").secure(true).with(httpBasic(username, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/parking/zones").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/zones").secure(true)).andExpect(status().isOk());
    }

    @Test
    void administratorCreatesHierarchicalCatalogEntriesAndAuditsEachCreation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String zoneCode = "QA-Z-" + suffix;
        String zoneBody = "{\"code\":\"" + zoneCode + "\",\"name\":\"Zona QA\",\"description\":\"Fixture aislado\"}";
        MvcResult zoneResult = postJson("/api/v1/admin/parking/zones", zoneBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(zoneCode))
                .andExpect(jsonPath("$.active").value(true)).andReturn();
        UUID zoneId = UUID.fromString(json(zoneResult, "id"));

        String streetCode = "QA-S-" + suffix;
        MvcResult streetResult = postJson("/api/v1/admin/parking/zones/" + zoneId + "/streets",
                        "{\"code\":\"" + streetCode + "\",\"name\":\"Calle QA\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneId").value(zoneId.toString())).andReturn();
        UUID streetId = UUID.fromString(json(streetResult, "id"));

        String spaceCode = "QA-P-" + suffix;
        MvcResult spaceResult = postJson("/api/v1/admin/parking/streets/" + streetId + "/spaces",
                        "{\"code\":\"" + spaceCode + "\",\"qrCode\":\"QR-QA-" + suffix
                                + "\",\"spaceNumber\":\"01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streetId").value(streetId.toString()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andReturn();
        UUID spaceId = UUID.fromString(json(spaceResult, "id"));

        mvc.perform(get("/api/v1/admin/parking/zones/" + zoneId + "/streets").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(streetId.toString()));
        mvc.perform(get("/api/v1/admin/parking/streets/" + streetId + "/spaces").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(spaceId.toString()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_id=? AND action IN ('CONFIGURATION_ZONE_CREATED','CONFIGURATION_STREET_CREATED','CONFIGURATION_PARKING_SPACE_CREATED') AND result='SUCCESS'",
                Integer.class, adminId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking.parking_spaces WHERE id=?", Integer.class, spaceId)).isEqualTo(1);
    }

    @Test
    void administrativeCreateRequiresCsrfAndValidCoordinates() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String zoneBody = "{\"code\":\"QA-CSRF-" + suffix + "\",\"name\":\"Zona QA\"}";
        mvc.perform(post("/api/v1/admin/parking/zones").secure(true).cookie(sessionCookie)
                        .contentType("application/json").content(zoneBody))
                .andExpect(status().isForbidden());

        String badCoordinates = "{\"code\":\"QA-P-" + suffix + "\",\"qrCode\":\"QR-QA-" + suffix
                + "\",\"spaceNumber\":\"01\",\"latitude\":12.1}";
        mvc.perform(post("/api/v1/admin/parking/streets/" + UUID.randomUUID() + "/spaces").secure(true)
                        .cookie(sessionCookie, csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType("application/json").content(badCoordinates))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking.zones WHERE code=?", Integer.class,
                "QA-CSRF-" + suffix)).isZero();
    }

    @Test
    void inactiveParentCannotReceiveNewChildren() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult createdZone = postJson("/api/v1/admin/parking/zones",
                        "{\"code\":\"QA-INACTIVE-" + suffix + "\",\"name\":\"Zona aislada\"}")
                .andExpect(status().isCreated()).andReturn();
        UUID zoneId = UUID.fromString(json(createdZone, "id"));
        jdbc.update("UPDATE parking.zones SET active=FALSE WHERE id=?", zoneId);

        mvc.perform(post("/api/v1/admin/parking/zones/" + zoneId + "/streets").secure(true)
                        .cookie(sessionCookie, csrfCookie).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType("application/json")
                        .content("{\"code\":\"QA-S-" + suffix + "\",\"name\":\"Calle aislada\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking.streets WHERE zone_id=?", Integer.class,
                zoneId)).isZero();
    }

    @Test
    void paginatedCatalogIsAdminOnlyAndUpdatesUseOptimisticVersionAndAudit() throws Exception {
        mvc.perform(get("/api/v1/admin/parking/catalog/zones").secure(true)).andExpect(status().isUnauthorized());
        String suffix=UUID.randomUUID().toString().substring(0,8);
        MvcResult created=postJson("/api/v1/admin/parking/zones", "{\"code\":\"QA-PAGE-"+suffix+"\",\"name\":\"Zona paginada\"}")
                .andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json(created,"id"));
        mvc.perform(get("/api/v1/admin/parking/catalog/zones").secure(true).cookie(sessionCookie)
                        .param("q",suffix).param("page","0").param("size","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(result->assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store"));
        String expected=json(created,"updatedAt");
        mvc.perform(put("/api/v1/admin/parking/zones/"+id).secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json")
                        .content("{\"code\":\"QA-PAGE-"+suffix+"\",\"name\":\"Zona editada\",\"active\":true,\"expectedUpdatedAt\":\""+expected+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Zona editada"));
        assertThat(jdbc.queryForObject("SELECT name FROM parking.zones WHERE id=?",String.class,id)).isEqualTo("Zona editada");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action='CONFIGURATION_ZONE_UPDATED' AND resource_id=? AND result='SUCCESS'",Integer.class,id)).isEqualTo(1);
        mvc.perform(put("/api/v1/admin/parking/zones/"+id).secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json")
                        .content("{\"code\":\"QA-PAGE-"+suffix+"\",\"name\":\"Cambios obsoletos\",\"active\":true,\"expectedUpdatedAt\":\""+expected+"\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT name FROM parking.zones WHERE id=?",String.class,id)).isEqualTo("Zona editada");
    }

    @Test
    void paginatedSpaceCatalogOrdersDistinctRowsUsingSelectedSortExpressions() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult zone = postJson("/api/v1/admin/parking/zones",
                "{\"code\":\"QA-SPAGE-" + suffix + "\",\"name\":\"Zona espacios\"}")
                .andExpect(status().isCreated()).andReturn();
        UUID zoneId = UUID.fromString(json(zone, "id"));
        MvcResult street = postJson("/api/v1/admin/parking/zones/" + zoneId + "/streets",
                "{\"code\":\"QA-SPAGE-ST-" + suffix + "\",\"name\":\"Calle espacios\"}")
                .andExpect(status().isCreated()).andReturn();
        UUID streetId = UUID.fromString(json(street, "id"));
        MvcResult space = postJson("/api/v1/admin/parking/streets/" + streetId + "/spaces",
                "{\"code\":\"QA-SPAGE-P-" + suffix + "\",\"qrCode\":\"QA-SPAGE-QR-" + suffix
                        + "\",\"spaceNumber\":\"01\"}")
                .andExpect(status().isCreated()).andReturn();
        UUID spaceId = UUID.fromString(json(space, "id"));

        mvc.perform(get("/api/v1/admin/parking/catalog/spaces").secure(true).cookie(sessionCookie)
                        .param("zoneId", zoneId.toString()).param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(spaceId.toString()))
                .andExpect(jsonPath("$.content[0].operationalStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.content[0].mapStatus").value("NO_ACTIVE_SESSION"));
    }

    @Test
    void administrativeSpaceGeneratesStableQrAndRejectsDuplicateCoordinatesWhileMapReadsPersistedPoints() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        MvcResult zone=postJson("/api/v1/admin/parking/zones","{\"code\":\"QA-MAP-Z-"+suffix+"\",\"name\":\"Zona mapa sintética\"}").andExpect(status().isCreated()).andReturn();
        UUID zoneId=UUID.fromString(json(zone,"id"));
        MvcResult street=postJson("/api/v1/admin/parking/zones/"+zoneId+"/streets","{\"code\":\"QA-MAP-S-"+suffix+"\",\"name\":\"Calle mapa sintética\"}").andExpect(status().isCreated()).andReturn();
        UUID streetId=UUID.fromString(json(street,"id"));
        String body="{\"code\":\"QA-MAP-P-"+suffix+"\",\"qrCode\":\"CLIENT-SUPPLIED-SHOULD-BE-IGNORED\",\"spaceNumber\":\"01\",\"latitude\":-3.672,\"longitude\":-79.652}";
        MvcResult created=postJson("/api/v1/admin/parking/streets/"+streetId+"/spaces",body).andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json(created,"id"));
        String qr=json(created,"qrCode");
        assertThat(qr).isEqualTo("SIMERTPI:SPACE:"+id);
        assertThat(jdbc.queryForObject("SELECT qr_code FROM parking.parking_spaces WHERE id=?",String.class,id)).isEqualTo(qr);
        mvc.perform(get("/api/v1/parking-spaces/qr/"+org.springframework.web.util.UriUtils.encodePathSegment(qr,java.nio.charset.StandardCharsets.UTF_8)).secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.qrCode").value(qr));

        mvc.perform(get("/api/v1/admin/parking/catalog/map").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/parking/catalog/map").secure(true).cookie(sessionCookie).param("zoneId",zoneId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalSpaces").value(1))
                .andExpect(jsonPath("$.georeferencedSpaces").value(1)).andExpect(jsonPath("$.markers[0].id").value(id.toString()))
                .andExpect(jsonPath("$.markers[0].operationalStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.markers[0].mapStatus").value("NO_ACTIVE_SESSION"))
                .andExpect(jsonPath("$.evaluatedAt").exists()).andExpect(jsonPath("$.refreshIntervalSeconds").value(60));
        mvc.perform(get("/api/v1/admin/parking/catalog/spaces/nearby").secure(true).cookie(sessionCookie)
                        .param("latitude","-3.672").param("longitude","-79.652").param("radiusMeters","3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("QA-MAP-P-"+suffix));

        String duplicate="{\"code\":\"QA-MAP-DUP-"+suffix+"\",\"spaceNumber\":\"02\",\"latitude\":-3.6720000,\"longitude\":-79.6520000}";
        mvc.perform(post("/api/v1/admin/parking/streets/"+streetId+"/spaces").secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json").content(duplicate))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_spaces WHERE code LIKE 'QA-MAP-DUP-%'",Integer.class)).isZero();

        String update="{\"code\":\"QA-MAP-P-"+suffix+"\",\"spaceNumber\":\"01\",\"latitude\":-3.672,\"longitude\":-79.652,\"spaceType\":\"STANDARD\",\"active\":true,\"expectedUpdatedAt\":\""+json(created,"updatedAt")+"\"}";
        mvc.perform(put("/api/v1/admin/parking/spaces/"+id).secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json").content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.qrCode").value(qr));
        assertThat(jdbc.queryForObject("SELECT qr_code FROM parking.parking_spaces WHERE id=?",String.class,id)).isEqualTo(qr);
    }

    @Test
    void mapAndSpaceCatalogShareServerEvaluatedSessionLifecycleOnPostgres() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        MvcResult z=postJson("/api/v1/admin/parking/zones","{\"code\":\"QA-MAP-STATE-Z-"+suffix+"\",\"name\":\"Zona estados mapa\"}").andExpect(status().isCreated()).andReturn();
        UUID zoneId=UUID.fromString(json(z,"id"));
        MvcResult st=postJson("/api/v1/admin/parking/zones/"+zoneId+"/streets","{\"code\":\"QA-MAP-STATE-S-"+suffix+"\",\"name\":\"Calle estados mapa\"}").andExpect(status().isCreated()).andReturn();
        UUID streetId=UUID.fromString(json(st,"id"));
        double latitude=-3.671+(Integer.parseInt(suffix.substring(0,4),16)%1000)*0.000001d;
        double longitude=-79.651+(Integer.parseInt(suffix.substring(4,8),16)%1000)*0.000001d;
        String coordinates=String.format(java.util.Locale.ROOT,"\"latitude\":%.6f,\"longitude\":%.6f",latitude,longitude);
        MvcResult sp=postJson("/api/v1/admin/parking/streets/"+streetId+"/spaces","{\"code\":\"QA-MAP-STATE-P-"+suffix+"\",\"spaceNumber\":\"01\","+coordinates+"}").andExpect(status().isCreated()).andReturn();
        UUID spaceId=UUID.fromString(json(sp,"id")); UUID vehicleId=UUID.randomUUID(); UUID sessionId=UUID.randomUUID();
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,active) VALUES (?,?,?,TRUE)",vehicleId,adminId,"MST"+suffix.substring(0,6));
        OffsetDateTime now=OffsetDateTime.now();
        jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status,total_amount) VALUES (?,?,?,?,?,?,?,0)",sessionId,adminId,vehicleId,spaceId,now.minusMinutes(30),now.plusHours(1),"ACTIVE");
        assertMapAndCatalogState(zoneId,streetId,spaceId,"ACTIVE");

        jdbc.update("UPDATE parking.parking_sessions SET status='PENDING_PAYMENT',expected_end_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",sessionId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"PAYMENT_PENDING");
        jdbc.update("UPDATE parking.parking_sessions SET status='ACTIVE',expected_end_at=CURRENT_TIMESTAMP+INTERVAL '1 hour' WHERE id=?",sessionId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"ACTIVE");

        jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds' WHERE id=?",sessionId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"ENDING_SOON");

        jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",sessionId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"EXPIRED");

        jdbc.update("UPDATE parking.parking_sessions SET status='MAX_TIME_REACHED' WHERE id=?",sessionId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"MAX_TIME_REACHED");

        jdbc.update("UPDATE parking.parking_spaces SET active=FALSE WHERE id=?",spaceId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"DISABLED");
        jdbc.update("UPDATE parking.parking_spaces SET active=TRUE WHERE id=?",spaceId);

        jdbc.update("UPDATE parking.parking_sessions SET status='COMPLETED',ended_at=CURRENT_TIMESTAMP WHERE id=?",sessionId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"NO_ACTIVE_SESSION");

        jdbc.update("UPDATE parking.parking_spaces SET active=FALSE WHERE id=?",spaceId);
        assertMapAndCatalogState(zoneId,streetId,spaceId,"DISABLED");
    }

    private void assertMapAndCatalogState(UUID zoneId,UUID streetId,UUID spaceId,String expected) throws Exception {
        mvc.perform(get("/api/v1/admin/parking/catalog/map").secure(true).cookie(sessionCookie)
                        .param("zoneId",zoneId.toString()).param("streetId",streetId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.markers[0].mapStatus").value(expected));
        mvc.perform(get("/api/v1/admin/parking/catalog/spaces").secure(true).cookie(sessionCookie)
                        .param("zoneId",zoneId.toString()).param("streetId",streetId.toString()).param("size","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(spaceId.toString()))
                .andExpect(jsonPath("$.content[0].mapStatus").value(expected));
    }

    @Test
    void coordinatePairConstraintRejectsDuplicatesUnderConcurrencyButAllowsNearbySpaces() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        MvcResult zone=postJson("/api/v1/admin/parking/zones","{\"code\":\"QA-COORD-Z-"+suffix+"\",\"name\":\"Zona coordenadas\"}").andExpect(status().isCreated()).andReturn();
        UUID zoneId=UUID.fromString(json(zone,"id"));
        MvcResult street=postJson("/api/v1/admin/parking/zones/"+zoneId+"/streets","{\"code\":\"QA-COORD-S-"+suffix+"\",\"name\":\"Calle coordenadas\"}").andExpect(status().isCreated()).andReturn();
        UUID streetId=UUID.fromString(json(street,"id"));

        postJson("/api/v1/admin/parking/streets/"+streetId+"/spaces",
                "{\"code\":\"QA-COORD-BASE-"+suffix+"\",\"spaceNumber\":\"01\",\"latitude\":-3.67,\"longitude\":-79.65}")
                .andExpect(status().isCreated());
        postJson("/api/v1/admin/parking/streets/"+streetId+"/spaces",
                "{\"code\":\"QA-COORD-NEAR-"+suffix+"\",\"spaceNumber\":\"02\",\"latitude\":-3.670005,\"longitude\":-79.65}")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/admin/parking/catalog/spaces/nearby").secure(true).cookie(sessionCookie)
                        .param("latitude","-3.67").param("longitude","-79.65").param("radiusMeters","3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,latitude,longitude) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),streetId,"QA-COORD-SQL-DUP-"+suffix,"QA-COORD-SQL-QR-"+suffix,"99",-3.67,-79.65))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        var executor=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        var nextNumber=new java.util.concurrent.atomic.AtomicInteger(3);
        try {
            var request=(java.util.concurrent.Callable<Integer>)()->{
                ready.countDown();start.await();
                int number=nextNumber.getAndIncrement();String code="QA-COORD-RACE-"+suffix+"-"+number;
                String body="{\"code\":\""+code+"\",\"spaceNumber\":\""+number+"\",\"latitude\":-3.671,\"longitude\":-79.651}";
                return mvc.perform(post("/api/v1/admin/parking/streets/"+streetId+"/spaces").secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json").content(body))
                        .andReturn().getResponse().getStatus();
            };
            var first=executor.submit(request);var second=executor.submit(request);
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(201,409);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_spaces WHERE latitude=-3.671 AND longitude=-79.651",Integer.class)).isEqualTo(1);
    }

    @Test
    void disablingZoneWithOpenSessionIsRejectedWithoutPartialChanges() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        MvcResult z=postJson("/api/v1/admin/parking/zones", "{\"code\":\"QA-OPEN-"+suffix+"\",\"name\":\"Zona abierta\"}").andExpect(status().isCreated()).andReturn();
        UUID zoneId=UUID.fromString(json(z,"id"));
        MvcResult st=postJson("/api/v1/admin/parking/zones/"+zoneId+"/streets", "{\"code\":\"S\",\"name\":\"Calle\"}").andExpect(status().isCreated()).andReturn();
        UUID streetId=UUID.fromString(json(st,"id"));
        MvcResult sp=postJson("/api/v1/admin/parking/streets/"+streetId+"/spaces", "{\"code\":\"QA-SPACE-"+suffix+"\",\"qrCode\":\"QA-QR-"+suffix+"\",\"spaceNumber\":\"1\"}").andExpect(status().isCreated()).andReturn();
        UUID spaceId=UUID.fromString(json(sp,"id"));
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,active) VALUES (?,?,?,TRUE)",UUID.randomUUID(),adminId,"QA"+suffix.substring(0,6));
        UUID vehicleId=jdbc.queryForObject("SELECT id FROM identity.vehicles WHERE user_id=? AND plate=?",UUID.class,adminId,"QA"+suffix.substring(0,6));
        OffsetDateTime now=OffsetDateTime.now();
        jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status,total_amount) VALUES (?,?,?,?,?,?,?,0)",UUID.randomUUID(),adminId,vehicleId,spaceId,now,now.plusHours(1),"ACTIVE");
        mvc.perform(put("/api/v1/admin/parking/zones/"+zoneId).secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json")
                        .content("{\"code\":\"QA-OPEN-"+suffix+"\",\"name\":\"Zona abierta\",\"active\":false,\"expectedUpdatedAt\":\""+json(z,"updatedAt")+"\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT active FROM parking.zones WHERE id=?",Boolean.class,zoneId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM parking.parking_sessions WHERE parking_space_id=?",String.class,spaceId)).isEqualTo("ACTIVE");
        mvc.perform(put("/api/v1/admin/parking/streets/"+streetId).secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json")
                        .content("{\"code\":\"S\",\"name\":\"Calle\",\"active\":false,\"expectedUpdatedAt\":\""+json(st,"updatedAt")+"\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/admin/parking/spaces/"+spaceId).secure(true).cookie(sessionCookie,csrfCookie)
                        .header("X-XSRF-TOKEN",csrfCookie.getValue()).contentType("application/json")
                        .content("{\"code\":\"QA-SPACE-"+suffix+"\",\"qrCode\":\"QA-QR-"+suffix+"\",\"spaceNumber\":\"1\",\"spaceType\":\"STANDARD\",\"active\":false,\"expectedUpdatedAt\":\""+json(sp,"updatedAt")+"\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT active FROM parking.streets WHERE id=?",Boolean.class,streetId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM parking.parking_spaces WHERE id=?",Boolean.class,spaceId)).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).secure(true).cookie(sessionCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType("application/json").content(body));
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String path,String body) throws Exception {
        return mvc.perform(put(path).secure(true).cookie(sessionCookie,csrfCookie).header("X-XSRF-TOKEN",csrfCookie.getValue())
                .contentType("application/json").content(body));
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }

    private static String json(MvcResult result, String field) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$." + field);
    }
}
