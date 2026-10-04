package ec.gob.simertpi.api.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHardeningHttpIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "security-hardening-test";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    private final UUID citizenId = UUID.randomUUID();
    private final UUID inspectorId = UUID.randomUUID();
    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private String citizenName;
    private String inspectorName;

    @BeforeEach
    void setUp() {
        citizenName = "security-citizen-" + tag;
        inspectorName = "security-inspector-" + tag;
        OffsetDateTime now = OffsetDateTime.now();
        insertUser(citizenId, citizenName, now);
        insertUser(inspectorId, inspectorName, now);
        UUID citizenRole = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = 'CITIZEN'", UUID.class);
        UUID inspectorRole = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = 'INSPECTOR'", UUID.class);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", citizenId, citizenRole);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", inspectorId, inspectorRole);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?, ?)", citizenId, inspectorId);
        jdbc.update("DELETE FROM identity.users WHERE id IN (?, ?)", citizenId, inspectorId);
    }

    @Test
    void leavesCatalogPublicAndRequiresAuthenticationForProtectedRoutes() {
        assertThat(request(HttpMethod.GET, "/api/v1/zones", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/actuator/health", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/actuator/info", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.GET, "/api/v1/parking-sessions", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.GET, "/api/v1/parking-sessions", "unknown-user", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.GET, "/api/v1/payments/" + UUID.randomUUID(), null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void enforcesInspectorAndAdministrativeAuthorities() {
        String lookup = "/api/v1/inspections/lookup?plate=NOT-REGISTERED";
        assertThat(request(HttpMethod.GET, lookup, inspectorName, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, lookup, citizenName, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.POST, "/api/v1/payments", inspectorName, "{}")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders headers = authenticatedHeaders(citizenName);
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange(url("/api/v1/zones"), HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void generatesCorrelationIdAndEchoesClientCorrelationId() {
        ResponseEntity<String> generated = request(HttpMethod.GET, "/api/v1/zones", null, null);
        assertThat(generated.getHeaders().getFirst(CORRELATION_HEADER)).isNotBlank();

        String supplied = "client-trace-42:abc_123";
        HttpHeaders headers = new HttpHeaders();
        headers.set(CORRELATION_HEADER, supplied);
        ResponseEntity<String> echoed = restTemplate.exchange(url("/api/v1/zones"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(echoed.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo(supplied);
    }

    private ResponseEntity<String> request(HttpMethod method, String path, String username, Object body) {
        HttpHeaders headers = username == null ? new HttpHeaders() : authenticatedHeaders(username);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, PASSWORD);
        return headers;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private void insertUser(UUID id, String username, OffsetDateTime now) {
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                id, username, username + "@example.test", passwordEncoder.encode(PASSWORD), "Security", "Test", now, now);
    }
}
