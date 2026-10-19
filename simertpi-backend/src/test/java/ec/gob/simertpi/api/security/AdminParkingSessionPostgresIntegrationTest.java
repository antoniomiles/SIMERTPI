package ec.gob.simertpi.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.testsupport.AdminSessionTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminParkingSessionPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-session-admin-password-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private String adminUsername;
    private AdminSessionTestSupport.Session adminSession;

    @BeforeEach
    void loginAdministrator() throws Exception {
        adminUsername = "session-admin-" + UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                adminId, adminUsername, adminUsername + "@example.invalid", encoder.encode(PASSWORD), "Test", "Administrator");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", adminId);
        adminSession = AdminSessionTestSupport.login(mvc, adminUsername, PASSWORD);
    }

    @Test
    void sessionsRequireAdministratorSessionAndAreNotCacheable() throws Exception {
        mvc.perform(get("/api/v1/admin/parking/sessions").secure(true))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/parking/sessions").secure(true).cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void paginatesFiltersByPlateStatusAndInclusiveMunicipalDatesAndMinimizesReturnedData() throws Exception {
        Fixture fixture = fixture("ACTIVE", true);
        LocalDate sessionDate = fixture.startedAt().atZoneSameInstant(ZoneId.of("America/Guayaquil")).toLocalDate();

        MvcResult result = mvc.perform(get("/api/v1/admin/parking/sessions")
                        .secure(true).cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("page", "0").param("size", "10").param("status", "ACTIVE")
                        .param("q", fixture.plate()).param("from", sessionDate.toString()).param("to", sessionDate.toString()))
                .andExpect(status().isOk()).andReturn();

        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("totalElements").asLong()).isEqualTo(1);
        assertThat(response.path("totalPages").asInt()).isEqualTo(1);
        JsonNode row = response.path("items").get(0);
        assertThat(row.path("id").asText()).isEqualTo(fixture.sessionId().toString());
        assertThat(row.path("plate").asText()).isEqualTo(fixture.plate());
        assertThat(row.path("zoneCode").asText()).isEqualTo(fixture.zoneCode());
        assertThat(row.path("streetName").asText()).isEqualTo(fixture.streetName());
        assertThat(row.path("spaceNumber").asText()).isEqualTo(fixture.spaceNumber());
        assertThat(row.path("sessionStatus").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("paymentStatus").asText()).isEqualTo("APPROVED");
        assertThat(row.path("durationMinutes").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(row.has("userId")).isFalse();
        assertThat(row.has("vehicleId")).isFalse();
        assertThat(row.has("paymentId")).isFalse();
        assertThat(row.has("paymentMethod")).isFalse();
        assertThat(row.has("providerTransactionId")).isFalse();
        assertThat(row.has("email")).isFalse();

        Long audited = jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_name=? AND action='ADMIN_PARKING_SESSIONS_SEARCHED' AND result='SUCCESS'",
                Long.class, adminUsername);
        assertThat(audited).isEqualTo(1L);
    }

    @Test
    void invalidStatusDateRangeAndPageSizeAreRejected() throws Exception {
        var cookie = AdminSessionTestSupport.sessionCookie(adminSession);
        mvc.perform(get("/api/v1/admin/parking/sessions").secure(true).cookie(cookie).param("status", "NOT_A_SESSION_STATE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/parking/sessions").secure(true).cookie(cookie).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/parking/sessions").secure(true).cookie(cookie)
                        .param("from", "2026-10-20").param("to", "2026-10-19"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inspectorCannotObtainAdministrativeSessionOrReadSessions() throws Exception {
        String inspector = "session-inspector-" + UUID.randomUUID();
        UUID inspectorId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                inspectorId, inspector, inspector + "@example.invalid", encoder.encode(PASSWORD), "Test", "Inspector");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='INSPECTOR'", inspectorId);
        MvcResult csrf = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isNoContent()).andReturn();
        Cookie csrfCookie = cookie(csrf, "__Host-SIMERTPI_ADMIN_XSRF");
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + inspector + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(login.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(value -> value.startsWith(AdminAuthenticationService.COOKIE + "="));
        mvc.perform(get("/api/v1/admin/parking/sessions").secure(true))
                .andExpect(status().isUnauthorized());
    }

    private Fixture fixture(String status, boolean withPayment) {
        UUID ownerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID streetId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String plate = "QA" + suffix;
        String zoneCode = "QA-ZN-" + suffix;
        String streetName = "QA Street " + suffix;
        String spaceNumber = "Q" + suffix.substring(0, 5);
        OffsetDateTime startedAt = OffsetDateTime.now().minusMinutes(45);
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,'Private','Owner',TRUE)",
                ownerId, "session-owner-" + suffix, "owner-" + suffix + "@example.invalid", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicleId, ownerId, plate);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zoneId, zoneCode, "QA Zone " + suffix);
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?, ?,TRUE)", streetId, zoneId, "QA-ST-" + suffix, streetName);
        jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,active) VALUES (?,?,?,?,?,TRUE)",
                spaceId, streetId, "QA-SP-" + suffix, "QA-QR-" + suffix, spaceNumber);
        jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status,total_amount,extension_count,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1.00,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sessionId, ownerId, vehicleId, spaceId, startedAt, startedAt.plusMinutes(60), status);
        if (withPayment) {
            UUID paymentId = UUID.randomUUID();
            jdbc.update("INSERT INTO payments.payments(id,parking_session_id,provider,idempotency_key,amount,currency,status,created_at,updated_at) VALUES (?,?, 'TEST', ?, 1.00, 'USD', 'APPROVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    paymentId, sessionId, "qa-session-" + paymentId);
        }
        return new Fixture(sessionId, plate, zoneCode, streetName, spaceNumber, startedAt);
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }

    private record Fixture(UUID sessionId, String plate, String zoneCode, String streetName,
                           String spaceNumber, OffsetDateTime startedAt) { }
}
