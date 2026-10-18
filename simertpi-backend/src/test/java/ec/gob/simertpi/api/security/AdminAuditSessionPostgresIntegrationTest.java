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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminAuditSessionPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-audit-admin-password-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private Cookie sessionCookie;
    private String username;
    private String eventAction;
    private UUID actorId;
    private UUID resourceId;

    @BeforeEach
    void createAdministratorSession() throws Exception {
        username = "audit-admin-" + UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        actorId = userId;
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                userId, username, username + "@example.invalid", encoder.encode(PASSWORD), "Audit", "Fixture");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", userId);

        eventAction = "AUDIT_PROJECTION_" + UUID.randomUUID();
        resourceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit.functional_audit_log
                  (id, actor_id, actor_name, actor_role, action, resource_type, resource_id,
                   result, correlation_id, source_ip, user_agent, metadata)
                VALUES (?, ?, 'Audit Fixture', 'SIMERTPI_ADMIN', ?, 'TEST_RESOURCE', ?,
                        'SUCCESS', 'audit-projection-test', '203.0.113.10',
                        'SENSITIVE_USER_AGENT_MARKER', CAST('{\"sensitiveMarker\":\"SENSITIVE_METADATA_MARKER\"}' AS jsonb))
                """, UUID.randomUUID(), userId, eventAction, resourceId);

        MvcResult csrf = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        Cookie csrfCookie = cookie(csrf, "__Host-SIMERTPI_ADMIN_XSRF");
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        sessionCookie = cookie(login, AdminAuthenticationService.COOKIE);
    }

    @Test
    void administrativeAuditUsesTheExistingCookieSessionAndRejectsRequestsWithoutIt() throws Exception {
        mvc.perform(get("/api/v1/admin/audit").secure(true))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/audit").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void administrativeAuditReturnsOnlyFieldsRequiredByThePortal() throws Exception {
        String response = mvc.perform(get("/api/v1/admin/audit").secure(true).cookie(sessionCookie)
                        .param("actorId", actorId.toString()).param("action", eventAction)
                        .param("resourceId", resourceId.toString()).param("limit", "51").param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$[0].actorName").value("Audit Fixture"))
                .andExpect(jsonPath("$[0].actorRole").value("SIMERTPI_ADMIN"))
                .andExpect(jsonPath("$[0].action").value(eventAction))
                .andExpect(jsonPath("$[0].resourceType").value("TEST_RESOURCE"))
                .andExpect(jsonPath("$[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$[0].correlationId").value("audit-projection-test"))
                .andExpect(jsonPath("$[0].actorId").doesNotExist())
                .andExpect(jsonPath("$[0].resourceId").doesNotExist())
                .andExpect(jsonPath("$[0].sourceIp").doesNotExist())
                .andExpect(jsonPath("$[0].userAgent").doesNotExist())
                .andExpect(jsonPath("$[0].metadata").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain(resourceId.toString(), "203.0.113.10", "SENSITIVE_USER_AGENT_MARKER",
                        "SENSITIVE_METADATA_MARKER");
    }

    @Test
    void legacyAuditRouteKeepsItsExistingResponseFieldsAndBearerBasicContract() throws Exception {
        mvc.perform(get("/api/v1/audit").with(httpBasic(username, PASSWORD)).param("action", eventAction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actorId").isNotEmpty())
                .andExpect(jsonPath("$[0].resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$[0].sourceIp").value("203.0.113.10"))
                .andExpect(jsonPath("$[0].userAgent").value("SENSITIVE_USER_AGENT_MARKER"))
                .andExpect(jsonPath("$[0].metadata")
                        .value(org.hamcrest.Matchers.containsString("SENSITIVE_METADATA_MARKER")));
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
}
