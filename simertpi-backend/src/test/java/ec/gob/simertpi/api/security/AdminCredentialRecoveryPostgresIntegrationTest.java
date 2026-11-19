package ec.gob.simertpi.api.security;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.application.identity.admin.AdminRecoveryMail;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "logging.level.root=WARN", "spring.jpa.show-sql=false",
        "simertpi.admin-auth.allowed-origin=https://portal.example.invalid"
})
@AutoConfigureMockMvc
class AdminCredentialRecoveryPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = "/api/v1/admin/auth";
    private static final String RECOVERY = AUTH + "/recovery";
    private static final String ORIGIN = "https://portal.example.invalid";
    private static final String OLD_PASSWORD = "fixture-original-password";
    private static final String NEW_PASSWORD = "fixture-replacement-password";
    private static final String CSRF_COOKIE = "__Host-SIMERTPI_ADMIN_XSRF";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean AdminRecoveryMail mail;

    private UUID id;
    private String username;
    private String email;
    private String source;
    private Cookie csrf;

    @BeforeEach
    void createFixture() throws Exception {
        id = UUID.randomUUID();
        username = "recovery-" + id;
        email = username + "@example.invalid";
        source = id.toString();
        createUser(id, username, email, "SIMERTPI_ADMIN", true);
        var response = mvc.perform(get(AUTH + "/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        csrf = response.getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
    }

    @Test
    void usernameRequestsAreIndistinguishableAndThrottled() throws Exception {
        String unknown = "unknown-" + id + "@example.invalid";
        String citizen = "citizen-" + id + "@example.invalid";
        String disabled = "disabled-" + id + "@example.invalid";
        createUser(UUID.randomUUID(), "citizen-" + id, citizen, "CITIZEN", true);
        createUser(UUID.randomUUID(), "disabled-" + id, disabled, "SIMERTPI_ADMIN", false);

        for (String address : new String[] {email, unknown, citizen, disabled, email.toUpperCase(java.util.Locale.ROOT)}) {
            var response = mvc.perform(request("/username", address))
                    .andExpect(status().isAccepted()).andReturn().getResponse();
            assertThat(response.getContentAsString()).isEmpty();
        }
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(email), anyString(), body.capture());
        assertThat(body.getValue()).contains(username);
        verify(mail, after(300).times(1)).send(anyString(), anyString(), anyString());

        mvc.perform(post(RECOVERY + "/username").secure(true)
                .contentType("application/json").content(emailJson(email)))
                .andExpect(status().isForbidden());
        mvc.perform(request("/username", email).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetIsSingleUseAndExistingLoginAndRoleContinueToWork() throws Exception {
        var login = mvc.perform(authPost("/login", loginBody(OLD_PASSWORD))).andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie oldSession = login.getCookie(AdminAuthenticationService.COOKIE);
        assertThat(oldSession).isNotNull();
        String token = requestReset();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity.admin_password_resets WHERE user_id = ?",
                String.class, id)).isEqualTo(AdminAuthenticationService.hash(token));

        mvc.perform(reset(token)).andExpect(status().isNoContent());
        mvc.perform(reset(token)).andExpect(status().isBadRequest());
        mvc.perform(get(AUTH + "/me").secure(true).cookie(oldSession)).andExpect(status().isUnauthorized());
        mvc.perform(authPost("/login", loginBody(OLD_PASSWORD))).andExpect(status().isUnauthorized());
        var newLogin = mvc.perform(authPost("/login", loginBody(NEW_PASSWORD))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(newLogin).contains(username, "SIMERTPI_ADMIN", "dashboard.view");
        assertThat(jdbc.queryForObject("SELECT email FROM identity.users WHERE id = ?", String.class, id))
                .isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.user_roles WHERE user_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void expiredResetLeavesTheOldPasswordInPlace() throws Exception {
        String token = requestReset();
        jdbc.update("UPDATE identity.admin_password_resets SET expires_at = now() - interval '1 second' WHERE user_id = ?", id);
        mvc.perform(reset(token)).andExpect(status().isBadRequest());
        mvc.perform(authPost("/login", loginBody(OLD_PASSWORD))).andExpect(status().isOk());
    }

    private String requestReset() throws Exception {
        mvc.perform(request("/password", email)).andExpect(status().isAccepted());
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(email), anyString(), body.capture());
        var token = Pattern.compile(Pattern.quote(ORIGIN + "/restablecer-contrasena#token=") + "([A-Za-z0-9_-]{43})")
                .matcher(body.getValue());
        assertThat(token.find()).isTrue();
        return token.group(1);
    }

    private void createUser(UUID userId, String name, String address, String role, boolean enabled) {
        jdbc.update("""
                INSERT INTO identity.users (id, username, email, password_hash, first_name, last_name, enabled)
                VALUES (?, ?, ?, ?, 'Recovery', 'Fixture', ?)
                """, userId, name, address, encoder.encode(OLD_PASSWORD), enabled);
        jdbc.update("INSERT INTO identity.user_roles (user_id, role_id) SELECT ?, id FROM identity.roles WHERE code = ?",
                userId, role);
    }

    private MockHttpServletRequestBuilder request(String path, String address) {
        return authPost("/recovery" + path, emailJson(address));
    }

    private MockHttpServletRequestBuilder reset(String token) {
        return authPost("/recovery/reset", """
                {"token":"%s","newPassword":"%s"}
                """.formatted(token, NEW_PASSWORD));
    }

    private String loginBody(String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private String emailJson(String address) {
        return """
                {"email":"%s"}
                """.formatted(address);
    }

    private MockHttpServletRequestBuilder authPost(String path, String body) {
        return post(AUTH + path).secure(true).cookie(csrf).header("Origin", ORIGIN)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body)
                .with(request -> { request.setRemoteAddr(source); return request; });
    }
}
