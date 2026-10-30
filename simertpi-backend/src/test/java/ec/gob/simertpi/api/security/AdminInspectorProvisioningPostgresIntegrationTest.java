package ec.gob.simertpi.api.security;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.testsupport.InvitationMailTestConfiguration;
import ec.gob.simertpi.infrastructure.identity.invitation.InMemoryInvitationEmailSender;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false",
        "simertpi.invitation-email.public-base-url=https://dev.example.invalid"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(InvitationMailTestConfiguration.class)
class AdminInspectorProvisioningPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String ADMIN_PASSWORD = "isolated-admin-password-2026";
    private static final String INSPECTOR_PASSWORD = "isolated-inspector-password-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired InMemoryInvitationEmailSender invitationEmailSender;

    private String admin;
    private UUID adminId;
    private Browser browser;

    private record Browser(Cookie session, Cookie csrf) { }

    @BeforeEach
    void prepareAdministrator() throws Exception {
        admin = "inspector-admin-" + UUID.randomUUID();
        adminId = createUser(admin, "SIMERTPI_ADMIN", true, ADMIN_PASSWORD);
        browser = loginAdmin();
    }

    @Test
    void adminCreatesPendingInspectorWithOnlyServerAssignedRoleThenActivatesAndLogsInMunicipally() throws Exception {
        String username = "new-inspector-" + UUID.randomUUID();
        String email = username + "@example.invalid";
        String body = """
                {"username":"%s","email":"%s","firstName":"Ana","lastName":"Prueba","phone":"+593 99 123 4567","role":"SIMERTPI_ADMIN","enabled":true}
                """.formatted(username, email);

        MvcResult created = createInspector(body);
        assertThat(created.getResponse().getStatus()).as(diagnostic(created)).isEqualTo(201);

        String response = created.getResponse().getContentAsString();
        String id = json(response, "id");
        String token = latestToken(username);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(response).doesNotContain("passwordHash", "password", token, "activationToken");
        assertThat(json(response, "deliveryStatus")).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");

        UUID userId = UUID.fromString(id);
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, userId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash IS NULL FROM identity.users WHERE id=?", Boolean.class, userId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='INSPECTOR'", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
        String storedHash = sha256(token);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity.inspector_activation_tokens WHERE user_id=?", String.class, userId)).isEqualTo(storedHash);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity.inspector_activation_tokens WHERE user_id=?", String.class, userId)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='INSPECTOR_CREATED' AND actor_id=? AND resource_id=? AND result='SUCCESS'", Integer.class, adminId, userId)).isEqualTo(1);

        mvc.perform(post("/api/v1/municipal/auth/activate").secure(true).contentType("application/json")
                        .content("{\"activationToken\":\"" + token + "\",\"newPassword\":\"" + INSPECTOR_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, userId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE id=?", String.class, userId))
                .satisfies(hash -> assertThat(encoder.matches(INSPECTOR_PASSWORD, hash)).isTrue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.inspector_activation_tokens WHERE user_id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='INSPECTOR_ACTIVATED' AND resource_id=? AND result='SUCCESS'", Integer.class, userId)).isEqualTo(1);

        String mobileLogin = mvc.perform(post("/api/v1/municipal/auth/login").secure(true)
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + INSPECTOR_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access = json(mobileLogin, "accessToken");
        mvc.perform(get("/api/v1/municipal/auth/me").secure(true).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/inspectors").secure(true).header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/municipal/auth/activate").secure(true).contentType("application/json")
                        .content("{\"activationToken\":\"" + token + "\",\"newPassword\":\"" + INSPECTOR_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedRequestsAreRejectedAndRepeatedCreationConflictsWithoutDuplicateRows() throws Exception {
        String username = "duplicate-inspector-" + UUID.randomUUID();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.invalid\",\"firstName\":\"Test\",\"lastName\":\"Inspector\"}";
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/inspectors").secure(true).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        MvcResult created = createInspector(body);
        assertThat(created.getResponse().getStatus()).as(diagnostic(created)).isEqualTo(201);
        assertThat(createInspector(body).getResponse().getStatus()).isEqualTo(409);
        String sameEmailDifferentUsername = body.replace("\"username\":\"" + username + "\"",
                "\"username\":\"" + username + "-other\"");
        assertThat(createInspector(sameEmailDifferentUsername).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.users WHERE username=?", Integer.class, username)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.users WHERE email=?", Integer.class, username + "@example.invalid")).isEqualTo(1);
    }

    @Test
    void directoryFiltersOnlyInspectorsAndReissueInvalidatesPreviousActivationToken() throws Exception {
        String username = "pending-inspector-" + UUID.randomUUID();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.invalid\",\"firstName\":\"Pending\",\"lastName\":\"Inspector\"}";
        MvcResult created = createInspector(body);
        assertThat(created.getResponse().getStatus()).as(diagnostic(created)).isEqualTo(201);
        String response = created.getResponse().getContentAsString();
        String id = json(response, "id");
        String oldToken = latestToken(username);
        OffsetDateTime expiresAt = OffsetDateTime.parse(json(response, "activationExpiresAt"));
        assertThat(expiresAt).isAfter(OffsetDateTime.now().plusHours(23).minusMinutes(1));
        assertThat(expiresAt).isBefore(OffsetDateTime.now().plusHours(25));
        mvc.perform(get("/api/v1/admin/inspectors").secure(true).cookie(browser.session())
                        .param("q", username).param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].username").value(username))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].activationPending").value(true));

        mvc.perform(post("/api/v1/admin/inspectors/" + id + "/activation").secure(true)
                        .cookie(browser.session(), browser.csrf()).header("X-XSRF-TOKEN", browser.csrf().getValue()))
                .andExpect(status().isTooManyRequests());
        jdbc.update("UPDATE identity.inspector_activation_tokens SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE user_id=?", UUID.fromString(id));
        String renewed = mvc.perform(post("/api/v1/admin/inspectors/" + id + "/activation").secure(true)
                        .cookie(browser.session(), browser.csrf()).header("X-XSRF-TOKEN", browser.csrf().getValue()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newToken = latestToken(username);
        assertThat(renewed).doesNotContain(newToken, "activationToken");
        assertThat(json(renewed, "deliveryStatus")).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(newToken).isNotEqualTo(oldToken);
        mvc.perform(post("/api/v1/municipal/auth/activate").secure(true).contentType("application/json")
                        .content("{\"activationToken\":\"" + oldToken + "\",\"newPassword\":\"" + INSPECTOR_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/municipal/auth/activate").secure(true).contentType("application/json")
                        .content("{\"activationToken\":\"" + newToken + "\",\"newPassword\":\"" + INSPECTOR_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void expiredActivationTokenIsRejectedWithoutEnablingTheInspector() throws Exception {
        String username = "expired-inspector-" + UUID.randomUUID();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.invalid\",\"firstName\":\"Expired\",\"lastName\":\"Inspector\"}";
        MvcResult created = createInspector(body);
        assertThat(created.getResponse().getStatus()).as(diagnostic(created)).isEqualTo(201);
        String response = created.getResponse().getContentAsString();
        UUID userId = UUID.fromString(json(response, "id"));
        String token = latestToken(username);
        jdbc.update("UPDATE identity.inspector_activation_tokens SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?", userId);

        mvc.perform(post("/api/v1/municipal/auth/activate").secure(true).contentType("application/json")
                        .content("{\"activationToken\":\"" + token + "\",\"newPassword\":\"" + INSPECTOR_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, userId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash IS NULL FROM identity.users WHERE id=?", Boolean.class, userId)).isTrue();
    }

    @Test
    void activationValidationReportsExactOneDayLifetimeAndThrottlePersistsInvalidAttempts() throws Exception {
        String username = "validation-inspector-" + UUID.randomUUID();
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.invalid\",\"firstName\":\"Validation\",\"lastName\":\"Inspector\"}";
        MvcResult created = createInspector(body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String response = created.getResponse().getContentAsString();
        UUID userId = UUID.fromString(json(response, "id"));
        String token = latestToken(username);
        OffsetDateTime expiry = OffsetDateTime.parse(json(response, "activationExpiresAt"));
        assertThat(expiry).isAfter(OffsetDateTime.now().plusHours(24).minusSeconds(20));
        assertThat(expiry).isBefore(OffsetDateTime.now().plusHours(24).plusSeconds(20));
        mvc.perform(post("/api/v1/municipal/auth/activation/validate").secure(true).contentType("application/json")
                        .content("{\"activationToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("VALID"));

        String sourceAddress = "203.0.113." + (1 + Math.floorMod(username.hashCode(), 200));
        mvc.perform(post("/api/v1/municipal/auth/activate").secure(true).with(request -> {
                    request.setRemoteAddr(sourceAddress);
                    return request;
                }).contentType("application/json")
                        .content("{\"activationToken\":\"invalid\",\"newPassword\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        String attemptKey = AdminAuthenticationService.hash("activation-ip:" + sourceAddress);
        assertThat(jdbc.queryForObject("SELECT attempts FROM identity.activation_attempt_throttles WHERE attempt_key=?",
                Integer.class, attemptKey)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, userId)).isFalse();
    }

    @Test
    void invitationResendsHaveCooldownAndRollingDailyLimit() throws Exception {
        String username = "resend-limit-inspector-" + UUID.randomUUID();
        MvcResult created = createInspector("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.invalid\",\"firstName\":\"Resend\",\"lastName\":\"Inspector\"}");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID userId = UUID.fromString(json(created.getResponse().getContentAsString(), "id"));
        for (int resend = 0; resend < 4; resend++) {
            jdbc.update("UPDATE identity.inspector_activation_tokens SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE user_id=?", userId);
            mvc.perform(post("/api/v1/admin/inspectors/" + userId + "/activation").secure(true)
                            .cookie(browser.session(), browser.csrf()).header("X-XSRF-TOKEN", browser.csrf().getValue()))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.activation_delivery_attempts WHERE user_id=?",
                Integer.class, userId)).isEqualTo(5);
        jdbc.update("UPDATE identity.inspector_activation_tokens SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE user_id=?", userId);
        mvc.perform(post("/api/v1/admin/inspectors/" + userId + "/activation").secure(true)
                        .cookie(browser.session(), browser.csrf()).header("X-XSRF-TOKEN", browser.csrf().getValue()))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.activation_delivery_attempts WHERE user_id=?",
                Integer.class, userId)).isEqualTo(5);
    }

    private MvcResult createInspector(String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/inspectors").secure(true)
                .cookie(browser.session(), browser.csrf()).header("X-XSRF-TOKEN", browser.csrf().getValue())
                .contentType("application/json").content(body)).andReturn();
    }

    private static String diagnostic(MvcResult result) {
        Throwable failure = result.getResolvedException();
        while (failure != null && !(failure instanceof org.postgresql.util.PSQLException)) failure = failure.getCause();
        if (failure instanceof org.postgresql.util.PSQLException postgres) {
            var detail = postgres.getServerErrorMessage();
            return "SQLState=" + postgres.getSQLState() + ", constraint=" + (detail == null ? "unknown" : detail.getConstraint());
        }
        return "resolved=" + (result.getResolvedException() == null ? "none" : result.getResolvedException().getClass().getSimpleName());
    }

    private Browser loginAdmin() throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult response = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + admin + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return new Browser(cookie(response, AdminAuthenticationService.COOKIE), cookie(response, "__Host-SIMERTPI_ADMIN_XSRF"));
    }

    private Cookie csrfCookie() throws Exception {
        return cookie(mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isNoContent()).andReturn(), "__Host-SIMERTPI_ADMIN_XSRF");
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }

    private UUID createUser(String username, String role, boolean enabled, String password) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,?)",
                id, username, username + "@example.invalid", encoder.encode(password), "Isolated", "Fixture", enabled);
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?", id, role);
        return id;
    }

    private static String json(String json, String name) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(name).asText();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String latestToken(String username) {
        var message = invitationEmailSender.messages().stream().filter(item -> item.username().equals(username))
                .reduce((first, second) -> second).orElseThrow();
        String fragment = java.net.URI.create(message.activationUrl()).getRawFragment();
        assertThat(fragment).startsWith("token=");
        return fragment.substring("token=".length());
    }
}
