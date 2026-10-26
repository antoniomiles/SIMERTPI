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
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.content[0].operationalStatus").value("AVAILABLE"));
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
