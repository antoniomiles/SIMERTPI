package ec.gob.simertpi.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.identity.auth.MobileAuthService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MunicipalAuthPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "municipal-fixture-only";

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;

    private UUID inspectorId;
    private String inspectorName;

    @BeforeEach
    void createInspector() {
        inspectorId = UUID.randomUUID();
        inspectorName = "municipal-" + inspectorId;
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,true)",
                inspectorId, inspectorName, inspectorName + "@example.invalid", encoder.encode(PASSWORD), "Municipal", "Fixture");
        grant("INSPECTOR");
    }

    @Test
    void inspectorCanLoginReadMeAndUseInspectorEndpoint() throws Exception {
        JsonNode session = municipalLogin(inspectorName, PASSWORD);
        assertThat(jdbc.queryForObject("SELECT audience FROM identity.mobile_sessions WHERE user_id=?", String.class, inspectorId)).isEqualTo("MUNICIPAL");
        var me = get(session.get("accessToken").asText(), "/api/v1/municipal/auth/me");
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode identity = json.readTree(me.getBody());
        assertThat(identity.get("userId").asText()).isEqualTo(inspectorId.toString());
        assertThat(identity.get("roles").toString()).contains("INSPECTOR");
        assertThat(me.getHeaders().getCacheControl()).contains("no-store");
        assertThat(get(session.get("accessToken").asText(), "/api/v1/inspections/inspector/" + inspectorId).getStatusCode())
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void wrongPasswordDisabledCitizenOnlyAndNoInspectorAreRejected() {
        var invalid = login(inspectorName, "wrong-password");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getHeaders().getCacheControl()).contains("no-store");
        assertThat(login("missing-" + UUID.randomUUID(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE identity.users SET enabled=false WHERE id=?", inspectorId);
        assertThat(login(inspectorName, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE identity.users SET enabled=true WHERE id=?", inspectorId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?", inspectorId);
        grant("CITIZEN");
        assertThat(login(inspectorName, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?", inspectorId);
        assertThat(login(inspectorName, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refreshRotatesAndReuseRevokesMunicipalSession() throws Exception {
        JsonNode first = municipalLogin(inspectorName, PASSWORD);
        String refresh = first.get("refreshToken").asText();
        var renewedResponse = post("/api/v1/municipal/auth/refresh", Map.of("refreshToken", refresh));
        assertThat(renewedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode renewed = json.readTree(renewedResponse.getBody());
        assertThat(renewed.get("refreshToken").asText()).isNotEqualTo(refresh);
        assertThat(renewedResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(post("/api/v1/municipal/auth/refresh", Map.of("refreshToken", refresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(renewed.get("accessToken").asText(), "/api/v1/municipal/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredRefreshAndLogoutAreHandledSafely() throws Exception {
        JsonNode session = municipalLogin(inspectorName, PASSWORD);
        String refresh = session.get("refreshToken").asText();
        jdbc.update("UPDATE identity.mobile_sessions SET access_expires_at=CURRENT_TIMESTAMP-INTERVAL '2 seconds',refresh_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?", inspectorId);
        assertThat(post("/api/v1/municipal/auth/refresh", Map.of("refreshToken", refresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(session.get("accessToken").asText(), "/api/v1/municipal/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode second = municipalLogin(inspectorName, PASSWORD);
        String secondRefresh = second.get("refreshToken").asText();
        assertThat(post("/api/v1/municipal/auth/logout", Map.of("refreshToken", secondRefresh)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/api/v1/municipal/auth/logout", Map.of("refreshToken", secondRefresh)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/api/v1/municipal/auth/refresh", Map.of("refreshToken", secondRefresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(second.get("accessToken").asText(), "/api/v1/municipal/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void roleRemovalAndAccountDisableRevokeMunicipalAccess() throws Exception {
        JsonNode session = municipalLogin(inspectorName, PASSWORD);
        String access = session.get("accessToken").asText();
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?", inspectorId);
        assertThat(get(access, "/api/v1/municipal/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(access, "/api/v1/inspections/inspector/" + inspectorId).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM identity.mobile_sessions WHERE access_hash=?", Boolean.class,
                MobileAuthService.hash(access))).isTrue();

        grant("INSPECTOR");
        JsonNode second = municipalLogin(inspectorName, PASSWORD);
        String secondAccess = second.get("accessToken").asText();
        String refresh = second.get("refreshToken").asText();
        jdbc.update("UPDATE identity.users SET enabled=false WHERE id=?", inspectorId);
        assertThat(get(secondAccess, "/api/v1/inspections/inspector/" + inspectorId).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/v1/municipal/auth/refresh", Map.of("refreshToken", refresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM identity.mobile_sessions WHERE access_hash=?", Boolean.class, MobileAuthService.hash(secondAccess))).isTrue();
    }

    @Test
    void citizenAndMunicipalTokensCannotCrossAuthenticationScopesAndCitizenFlowRemains() throws Exception {
        grant("CITIZEN");
        JsonNode municipal = municipalLogin(inspectorName, PASSWORD);
        ResponseEntity<String> citizenResponse = post("/api/v1/auth/login", Map.of("username", inspectorName, "password", PASSWORD));
        assertThat(citizenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode citizen = json.readTree(citizenResponse.getBody());
        String municipalAccess = municipal.get("accessToken").asText();
        String citizenAccess = citizen.get("accessToken").asText();
        String municipalRefresh = municipal.get("refreshToken").asText();
        String citizenRefresh = citizen.get("refreshToken").asText();

        assertThat(get(citizenAccess, "/api/v1/municipal/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postWithBearer(citizenAccess, "/api/v1/inspections", Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(municipalAccess, "/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(municipalAccess, "/api/v1/zones").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/v1/municipal/auth/refresh", Map.of("refreshToken", citizenRefresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", municipalRefresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(citizenAccess, "/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", citizenRefresh)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void grant(String role) {
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?", inspectorId, role);
    }

    private ResponseEntity<String> login(String username, String password) {
        return post("/api/v1/municipal/auth/login", Map.of("username", username, "password", password));
    }

    private JsonNode municipalLogin(String username, String password) throws Exception {
        var response = login(username, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
        return json.readTree(response.getBody());
    }

    private ResponseEntity<String> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postWithBearer(String token, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
