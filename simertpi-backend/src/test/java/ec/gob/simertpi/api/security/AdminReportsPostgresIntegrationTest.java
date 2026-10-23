package ec.gob.simertpi.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.testsupport.AdminSessionTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminReportsPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-report-admin-password-2026";
    private static final OffsetDateTime START_ONE = OffsetDateTime.parse("2026-10-05T02:30:00Z");
    private static final OffsetDateTime START_TWO = OffsetDateTime.parse("2026-10-05T05:30:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private String adminUsername;
    private AdminSessionTestSupport.Session adminSession;

    @BeforeEach
    void loginAdministrator() throws Exception {
        adminUsername = "reports-admin-" + UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                id, adminUsername, adminUsername + "@example.invalid", encoder.encode(PASSWORD), "Test", "Administrator");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", id);
        adminSession = AdminSessionTestSupport.login(mvc, adminUsername, PASSWORD);
    }

    @Test
    void requiresAdminSessionRoleAndNoStore() throws Exception {
        mvc.perform(get("/api/v1/admin/reports").secure(true).param("from", "2026-10-04").param("to", "2026-10-05"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/reports").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .header("Origin", "https://untrusted.invalid")
                        .param("from", "2026-10-04").param("to", "2026-10-05"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/admin/reports").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("from", "2026-10-04").param("to", "2026-10-05"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void aggregatesOperationalAndFinancialDataByMunicipalDaysAndCurrencyWithoutAttemptDuplication() throws Exception {
        Fixture fixture = fixture();
        MvcResult result = mvc.perform(get("/api/v1/admin/reports").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("from", "2026-10-04").param("to", "2026-10-05"))
                .andExpect(status().isOk()).andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());

        JsonNode operational = response.path("operational");
        assertThat(operational.path("sessionsStarted").asLong()).isEqualTo(2L);
        assertThat(find(operational.path("sessionsByStatus"), "code", "COMPLETED").path("count").asLong()).isEqualTo(1L);
        assertThat(find(operational.path("sessionsByStatus"), "code", "ACTIVE").path("count").asLong()).isEqualTo(1L);
        assertThat(find(operational.path("sessionsByZone"), "zoneCode", fixture.zoneCode()).path("count").asLong()).isEqualTo(2L);
        assertThat(operational.path("dailySessions").size()).isEqualTo(2);
        assertThat(operational.path("dailySessions").get(0).path("date").asText()).isEqualTo("2026-10-04");
        assertThat(operational.path("dailySessions").get(0).path("count").asLong()).isEqualTo(1L);
        assertThat(operational.path("dailySessions").get(1).path("date").asText()).isEqualTo("2026-10-05");
        assertThat(operational.path("dailySessions").get(1).path("count").asLong()).isEqualTo(1L);
        assertThat(operational.path("recordedDuration").path("endedSessionCount").asLong()).isEqualTo(1L);
        assertThat(operational.path("recordedDuration").path("totalSeconds").asText()).isEqualTo("5400.000000");
        JsonNode spaces = find(operational.path("spacesByZone"), "zoneCode", fixture.zoneCode());
        assertThat(spaces.path("configuredSpaces").asLong()).isEqualTo(3L);
        assertThat(spaces.path("activeSpaces").asLong()).isEqualTo(2L);

        JsonNode financial = response.path("financial");
        JsonNode approvedUsd = findPayment(financial.path("approvedPaymentsByPaidAt"), "USD");
        JsonNode approvedEur = findPayment(financial.path("approvedPaymentsByPaidAt"), "EUR");
        assertThat(approvedUsd.path("count").asLong()).isEqualTo(1L);
        assertThat(approvedUsd.path("amount").asText()).isEqualTo("10.25");
        assertThat(approvedEur.path("amount").asText()).isEqualTo("1.10");
        assertThat(financial.path("dailyApprovedPayments").size()).isEqualTo(4);
        JsonNode byZoneUsd = findZoneCurrency(financial.path("approvedPaymentsByZone"), fixture.zoneCode(), "USD");
        assertThat(byZoneUsd.path("count").asLong()).isEqualTo(1L);
        assertThat(byZoneUsd.path("amount").asText()).isEqualTo("10.25");
        JsonNode refunded = findPaymentStatus(financial.path("paymentsByCurrentStatus"), "REFUNDED", "USD");
        assertThat(refunded.path("count").asLong()).isEqualTo(1L);
        assertThat(refunded.path("amount").asText()).isEqualTo("3.00");
        assertThat(response.path("timeZone").asText()).isEqualTo("America/Guayaquil");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("REPORT-PRIVATE", "attempt", "providerTransactionId", "plate", "userId");

        Long audited = jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_name=? AND action='ADMIN_REPORTS_VIEWED' AND result='SUCCESS'",
                Long.class, adminUsername);
        assertThat(audited).isEqualTo(1L);
    }

    @Test
    void rejectsMissingInvalidReversedAndOverlongRanges() throws Exception {
        var cookie = AdminSessionTestSupport.sessionCookie(adminSession);
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(cookie)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(cookie).param("from", "2026-99-01").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(cookie).param("from", "2026-10-05").param("to", "2026-10-04"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(cookie).param("from", "2025-10-01").param("to", "2026-10-02"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditsEmptyQueriesWithoutInventingRows() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/admin/reports").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("from", "2020-01-01").param("to", "2020-01-02"))
                .andExpect(status().isOk()).andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("operational").path("sessionsStarted").asLong()).isZero();
        assertThat(response.path("financial").path("paymentsByCurrentStatus").isEmpty()).isTrue();
        assertThat(response.path("financial").path("approvedPaymentsByPaidAt").isEmpty()).isTrue();
    }

    @Test
    void testDatabaseIsMigratedThroughV47WithoutIntroducingLaterMigrations() {
        String latestVersion = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(latestVersion).isEqualTo("47");
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?, ?,TRUE)",
                owner, "reports-owner-" + suffix, "reports-owner-" + suffix + "@example.invalid", encoder.encode(PASSWORD), "Private", "Owner");
        UUID zone = UUID.randomUUID();
        String zoneCode = "RP-Z-" + suffix;
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zone, zoneCode, "Reports fixture");
        UUID street = UUID.randomUUID();
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?, ?,TRUE)", street, zone, "RP-S-" + suffix, "Reports fixture street");
        UUID[] spaces = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (int i = 0; i < spaces.length; i++) {
            jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,active) VALUES (?,?,?,?,?,?)",
                    spaces[i], street, "RP-P-" + suffix + "-" + i, "RP-QR-" + suffix + "-" + i, "R" + suffix.substring(0, 5) + i, i != 2);
        }
        UUID vehicleOne = vehicle(owner, "R1" + suffix.substring(0, 6));
        UUID vehicleTwo = vehicle(owner, "R2" + suffix.substring(0, 6));
        UUID sessionOne = session(owner, vehicleOne, spaces[0], START_ONE, START_ONE.plusSeconds(5400), "COMPLETED");
        UUID sessionTwo = session(owner, vehicleTwo, spaces[1], START_TWO, null, "ACTIVE");
        UUID outsideVehicle = vehicle(owner, "R3" + suffix.substring(0, 6));
        session(owner, outsideVehicle, spaces[2], OffsetDateTime.parse("2026-10-06T06:00:00Z"), null, "ACTIVE");
        payment(sessionOne, "10.25", "USD", "APPROVED", START_ONE.plusSeconds(3600), "REPORT-PRIVATE-" + suffix);
        payment(sessionTwo, "1.10", "EUR", "APPROVED", START_TWO, null);
        payment(sessionOne, "3.00", "USD", "REFUNDED", START_ONE.plusSeconds(1800), null);
        payment(sessionTwo, "0.75", "USD", "FAILED", START_TWO, null);
        return new Fixture(zoneCode);
    }

    private UUID vehicle(UUID owner, String plate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, owner, plate);
        return id;
    }

    private UUID session(UUID owner, UUID vehicle, UUID space, OffsetDateTime started,
                         OffsetDateTime ended, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,
                  ended_at,status,total_amount,extension_count,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?, ?,1.00,0,?,?)
                """, id, owner, vehicle, space, started, started.plusHours(2), ended, status, started, started);
        return id;
    }

    private void payment(UUID session, String amount, String currency, String status,
                         OffsetDateTime paidAt, String providerReference) {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = paidAt;
        jdbc.update("""
                INSERT INTO payments.payments(id,parking_session_id,provider,provider_transaction_id,idempotency_key,
                  amount,currency,status,created_at,updated_at,paid_at)
                VALUES (?,?,'TEST',?,?,?, ?,?,?,?,?)
                """, id, session, providerReference, "reports-idempotency-" + UUID.randomUUID(),
                new BigDecimal(amount), currency, status, createdAt, createdAt,
                "APPROVED".equals(status) || "REFUNDED".equals(status) ? paidAt : null);
        if ("APPROVED".equals(status)) {
            for (int i = 1; i <= 2; i++) {
                jdbc.update("INSERT INTO payments.payment_attempts(id,payment_id,attempt_number,status,created_at) VALUES (?,?,?,'APPROVED',?)",
                        UUID.randomUUID(), id, i, createdAt);
            }
        }
    }

    private static JsonNode find(JsonNode array, String property, String value) {
        for (JsonNode item : array) if (value.equals(item.path(property).asText())) return item;
        throw new AssertionError("Missing report row " + value);
    }

    private static JsonNode findPayment(JsonNode array, String currency) {
        return find(array, "currency", currency);
    }

    private static JsonNode findPaymentStatus(JsonNode array, String status, String currency) {
        for (JsonNode item : array) if (status.equals(item.path("status").asText()) && currency.equals(item.path("currency").asText())) return item;
        throw new AssertionError("Missing payment status summary");
    }

    private static JsonNode findZoneCurrency(JsonNode array, String zone, String currency) {
        for (JsonNode item : array) if (zone.equals(item.path("zoneCode").asText()) && currency.equals(item.path("currency").asText())) return item;
        throw new AssertionError("Missing zone currency summary");
    }

    private record Fixture(String zoneCode) { }
}
