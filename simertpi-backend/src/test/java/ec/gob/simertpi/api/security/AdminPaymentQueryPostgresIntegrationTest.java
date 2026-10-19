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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminPaymentQueryPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-payments-admin-password-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private String adminUsername;
    private AdminSessionTestSupport.Session adminSession;

    @BeforeEach
    void loginAdministrator() throws Exception {
        adminUsername = "payments-admin-" + UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                adminId, adminUsername, adminUsername + "@example.invalid", encoder.encode(PASSWORD), "Test", "Administrator");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", adminId);
        adminSession = AdminSessionTestSupport.login(mvc, adminUsername, PASSWORD);
    }

    @Test
    void requiresAdministrativeSessionAndDisablesCaching() throws Exception {
        mvc.perform(get("/api/v1/admin/payments").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/payments").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void returnsPaginatedFilteredRowsWithExactAmountsAndMinimalFields() throws Exception {
        Fixture fixture = fixture();
        LocalDate paymentDate = fixture.createdAt().atZoneSameInstant(ZoneId.of("America/Guayaquil")).toLocalDate();
        MvcResult result = mvc.perform(get("/api/v1/admin/payments")
                        .secure(true).cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("page", "0").param("size", "1").param("status", " approved ")
                        .param("q", fixture.sessionId().toString())
                        .param("from", paymentDate.toString()).param("to", paymentDate.toString()))
                .andExpect(status().isOk()).andReturn();

        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("totalElements").asLong()).isEqualTo(2L);
        assertThat(response.path("totalPages").asInt()).isEqualTo(2);
        assertThat(response.path("items").size()).isEqualTo(1);
        JsonNode row = response.path("items").get(0);
        assertThat(row.path("parkingSessionId").asText()).isEqualTo(fixture.sessionId().toString());
        assertThat(row.path("amount").asText()).isIn("9999999999.99", "1.23");
        assertThat(row.path("status").asText()).isEqualTo("APPROVED");
        assertThat(row.has("providerTransactionId")).isFalse();
        assertThat(row.has("providerPaymentId")).isFalse();
        assertThat(row.has("providerOperationKey")).isFalse();
        assertThat(row.has("idempotencyKey")).isFalse();
        assertThat(row.has("failureReason")).isFalse();
        assertThat(row.has("paymentMethod")).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("provider-sensitive-reference");

        Long audited = jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_name=? AND action='ADMIN_PAYMENTS_SEARCHED' AND result='SUCCESS'",
                Long.class, adminUsername);
        assertThat(audited).isEqualTo(1L);
    }

    @Test
    void summarizesStatesSeparatelyForEachCurrencyWithoutCallingApprovedAmountsDeposits() throws Exception {
        Fixture fixture = fixture();
        JsonNode response = authenticatedList(fixture.sessionId().toString());
        JsonNode summaries = response.path("summaryByCurrency");
        JsonNode usd = findCurrency(summaries, "USD");
        JsonNode eur = findCurrency(summaries, "EUR");

        assertThat(usd.path("approvedCount").asLong()).isEqualTo(1L);
        assertThat(usd.path("approvedAmount").asText()).isEqualTo("9999999999.99");
        assertThat(usd.path("pendingCount").asLong()).isEqualTo(2L);
        assertThat(usd.path("pendingAmount").asText()).isEqualTo("0.30");
        assertThat(usd.path("failedCount").asLong()).isEqualTo(2L);
        assertThat(usd.path("failedAmount").asText()).isEqualTo("0.90");
        assertThat(usd.path("refundedAmount").asText()).isEqualTo("0.60");
        assertThat(usd.path("cancelledAmount").asText()).isEqualTo("0.70");
        assertThat(eur.path("approvedAmount").asText()).isEqualTo("1.23");
        assertThat(response.toString()).doesNotContain("deposit", "settled", "commission", "provider-sensitive-reference");
    }

    @Test
    void rejectsUnknownStatusInvalidDatesAndUnsafePagination() throws Exception {
        var cookie = AdminSessionTestSupport.sessionCookie(adminSession);
        mvc.perform(get("/api/v1/admin/payments").secure(true).cookie(cookie).param("status", "SETTLED"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/payments").secure(true).cookie(cookie).param("from", "2026-99-45"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/payments").secure(true).cookie(cookie)
                        .param("from", "2026-10-20").param("to", "2026-10-19"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/payments").secure(true).cookie(cookie).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode authenticatedList(String sessionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/admin/payments").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession)).param("q", sessionId))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode findCurrency(JsonNode summaries, String currency) {
        for (JsonNode row : summaries) if (currency.equals(row.path("currency").asText())) return row;
        throw new AssertionError("Missing currency summary " + currency);
    }

    private Fixture fixture() {
        UUID ownerId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID streetId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneId.of("America/Guayaquil"))
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,'Private','Owner',TRUE)",
                ownerId, "payments-owner-" + suffix, "payments-owner-" + suffix + "@example.invalid", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicleId, ownerId, "PM" + suffix);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zoneId, "PM-ZN-" + suffix, "Payments QA");
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?, ?,TRUE)", streetId, zoneId, "PM-ST-" + suffix, "Payments QA Street");
        jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,active) VALUES (?,?,?,?,?,TRUE)",
                spaceId, streetId, "PM-SP-" + suffix, "PM-QR-" + suffix, "P" + suffix.substring(0, 5));
        jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status,total_amount,extension_count,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1.00,0,?,?)",
                sessionId, ownerId, vehicleId, spaceId, createdAt, createdAt.plusHours(1), "COMPLETED", createdAt, createdAt);

        payment(sessionId, "9999999999.99", "USD", "APPROVED", createdAt, "provider-sensitive-reference-" + suffix);
        payment(sessionId, "0.10", "USD", "PENDING", createdAt, null);
        payment(sessionId, "0.20", "USD", "PROCESSING", createdAt, null);
        payment(sessionId, "0.40", "USD", "DECLINED", createdAt, null);
        payment(sessionId, "0.50", "USD", "FAILED", createdAt, null);
        payment(sessionId, "0.60", "USD", "REFUNDED", createdAt, null);
        payment(sessionId, "0.70", "USD", "CANCELLED", createdAt, null);
        payment(sessionId, "1.23", "EUR", "APPROVED", createdAt, null);
        return new Fixture(sessionId, createdAt);
    }

    private void payment(UUID sessionId, String amount, String currency, String status,
                         OffsetDateTime createdAt, String providerReference) {
        jdbc.update("""
                INSERT INTO payments.payments(id,parking_session_id,provider,provider_transaction_id,idempotency_key,
                    amount,currency,status,created_at,updated_at,paid_at)
                VALUES (?,?, 'TEST', ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sessionId, providerReference,
                "test-idempotency-" + UUID.randomUUID(), new java.math.BigDecimal(amount), currency, status,
                createdAt, createdAt, "APPROVED".equals(status) ? createdAt : null);
    }

    private record Fixture(UUID sessionId, OffsetDateTime createdAt) { }
}
