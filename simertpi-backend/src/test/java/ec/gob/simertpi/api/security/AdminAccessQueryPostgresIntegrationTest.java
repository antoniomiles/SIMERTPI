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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminAccessQueryPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String ADMIN_PASSWORD = "isolated-access-admin-password";
    private static final String USER_PASSWORD = "isolated-account-password";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private String adminUsername;
    private UUID adminId;
    private Browser browser;

    private record Browser(Cookie session, Cookie csrf) { }

    @BeforeEach
    void prepareAdminSession() throws Exception {
        adminUsername = "access-admin-" + UUID.randomUUID();
        adminId = createUser(adminUsername, "IT_ADMIN", true, ADMIN_PASSWORD);
        browser = loginAdmin();
    }

    @Test
    void directoryFiltersAndPagesAccountsWithoutExposingCredentialOrContactSecrets() throws Exception {
        String key = "access-query-" + UUID.randomUUID();
        UUID citizen = createUser(key + "-citizen", "CITIZEN", true, USER_PASSWORD);
        UUID inspector = createUser(key + "-inspector", "INSPECTOR", false, null);

        MvcResult result = mvc.perform(get("/api/v1/admin/access/users").secure(true)
                        .cookie(browser.session()).param("q", key).param("role", "citizen")
                        .param("enabled", "true").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].id").value(citizen.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].roles[0]").value("CITIZEN"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].enabled").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.size").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalElements").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalPages").value(1))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String storedHash = jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE id=?", String.class, citizen);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body).contains("phone");
        assertThat(body).doesNotContain("passwordHash", "password_hash", "activationToken", storedHash);
        assertThat(jdbc.queryForObject("SELECT enabled FROM identity.users WHERE id=?", Boolean.class, inspector)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles WHERE user_id=?", Integer.class, citizen)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_id=? AND action='ADMIN_USERS_SEARCHED' AND result='SUCCESS'", Integer.class, adminId)).isPositive();
    }

    @Test
    void roleCatalogComesFromDatabaseAndDoesNotClaimConfigurablePermissions() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/admin/access/roles").secure(true)
                        .cookie(browser.session()))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body).contains("CITIZEN", "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN",
                "COLLECTIONS", "FINANCE", "IT_ADMIN", "AUDITOR", "assignedAccounts");
        assertThat(body).doesNotContain("permissions", "password", "activationToken");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.roles", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_id=? AND action='ADMIN_ROLES_VIEWED' AND result='SUCCESS'", Integer.class, adminId)).isPositive();
    }

    @Test
    void inspectorDirectoryWithoutSearchTermReturnsRecordsInsteadOfBindingNullAsBinary() throws Exception {
        String username = "access-inspector-directory-" + UUID.randomUUID();
        UUID inspector = createUser(username, "INSPECTOR", false, null);

        mvc.perform(get("/api/v1/admin/inspectors").secure(true).cookie(browser.session()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.content[?(@.id == '" + inspector + "')]").exists());
    }

    @Test
    void anonymousInspectorBearerAndMutationAttemptsCannotUseTheAdministrativeDirectory() throws Exception {
        mvc.perform(get("/api/v1/admin/access/users").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/access/roles").secure(true)).andExpect(status().isUnauthorized());

        String inspectorUsername = "access-inspector-" + UUID.randomUUID();
        createUser(inspectorUsername, "INSPECTOR", true, USER_PASSWORD);
        String mobileLogin = mvc.perform(post("/api/v1/municipal/auth/login").secure(true)
                        .contentType("application/json")
                        .content("{\"username\":\"" + inspectorUsername + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String accessToken = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mobileLogin)
                .get("accessToken").asText();
        mvc.perform(get("/api/v1/admin/access/users").secure(true)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        String municipalAdminUsername = "access-municipal-admin-" + UUID.randomUUID();
        createUser(municipalAdminUsername, "SIMERTPI_ADMIN", true, ADMIN_PASSWORD);
        Browser municipalAdmin = loginAs(municipalAdminUsername, ADMIN_PASSWORD);
        mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(municipalAdmin.session()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.username").value(municipalAdminUsername))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.roles").value(org.hamcrest.Matchers.contains("SIMERTPI_ADMIN")));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM identity.users", Integer.class);
        String escalationUsername = "access-escalation-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/admin/access/users").secure(true).cookie(municipalAdmin.session(), municipalAdmin.csrf())
                        .header("X-XSRF-TOKEN", municipalAdmin.csrf().getValue())
                        .contentType("application/json")
                        .content("{\"username\":\"" + escalationUsername + "\",\"email\":\"" + escalationUsername
                                + "@example.invalid\",\"firstName\":\"Escalation\",\"lastName\":\"Attempt\","
                                + "\"roles\":[\"SIMERTPI_ADMIN\"],\"reason\":\"Intento de elevaciÃ³n bloqueado\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.users", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_USERS_SEARCHED' AND actor_id=?", Integer.class, adminId)).isZero();
    }

    private Browser loginAdmin() throws Exception {
        return loginAs(adminUsername, ADMIN_PASSWORD);
    }

    private Browser loginAs(String username, String password) throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult result = mvc.perform(post("/api/v1/admin/auth/login").secure(true)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return new Browser(cookie(result, AdminAuthenticationService.COOKIE),
                cookie(result, "__Host-SIMERTPI_ADMIN_XSRF"));
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        return cookie(result, "__Host-SIMERTPI_ADMIN_XSRF");
    }

    private UUID createUser(String username, String role, boolean enabled, String password) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,?)",
                id, username, username + "@example.invalid", password == null ? null : encoder.encode(password),
                "Test", "Account", enabled);
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?", id, role);
        return id;
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
}
