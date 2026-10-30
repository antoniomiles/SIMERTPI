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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminDynamicAuthorizationPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-secretary-fixture-password";
    private static final String USERNAME = "dynamic-role-" + UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private UUID userId;

    @BeforeEach
    void createSecretaryFixture() {
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,true)",
                userId, USERNAME, USERNAME + "@example.invalid", encoder.encode(PASSWORD), "Secretaría", "Pruebas");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SECRETARIA'", userId);
    }

    @Test
    void customPermissionAuthorizesOnlyMappedReadAndRevocationInvalidatesActiveSession() throws Exception {
        var login = login();
        Cookie session = cookie(login, AdminAuthenticationService.COOKIE);
        mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("SECRETARIA"))
                .andExpect(jsonPath("$.permissions").isArray());
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(session)
                        .param("from", "2026-01-01").param("to", "2026-01-02"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/parking/tariffs").secure(true).cookie(session)).andExpect(status().isForbidden());

        jdbc.update("DELETE FROM identity.role_permissions WHERE role_id=(SELECT id FROM identity.roles WHERE code='SECRETARIA') AND permission_code='reports.view'");
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(session)
                        .param("from", "2026-01-01").param("to", "2026-01-02"))
                .andExpect(status().isUnauthorized());

        Cookie renewed = cookie(login(), AdminAuthenticationService.COOKIE);
        mvc.perform(get("/api/v1/admin/reports").secure(true).cookie(renewed)
                        .param("from", "2026-01-01").param("to", "2026-01-02"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/parking/zones").secure(true).cookie(renewed)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.admin_web_sessions WHERE user_id=? AND revoked_at IS NOT NULL", Integer.class, userId)).isPositive();
    }

    private MvcResult login() throws Exception {
        Cookie csrf = csrf();
        return mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private Cookie csrf() throws Exception {
        return cookie(mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isNoContent()).andReturn(),
                "__Host-SIMERTPI_ADMIN_XSRF");
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
}
