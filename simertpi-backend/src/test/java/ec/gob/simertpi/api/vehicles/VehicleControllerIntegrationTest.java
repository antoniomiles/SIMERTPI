package ec.gob.simertpi.api.vehicles;

import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VehicleControllerIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "vehicle-test-password";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;

    private UUID userId;
    private String username;
    private String inspectorUsername;
    private String otherUsername;
    private UUID otherUserId;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM identity.mobile_refresh_tokens WHERE session_id IN (SELECT id FROM identity.mobile_sessions WHERE user_id IN (SELECT id FROM identity.users WHERE username IN (?, ?, ?)))", username, inspectorUsername, otherUsername);
        jdbc.update("DELETE FROM identity.mobile_sessions WHERE user_id IN (SELECT id FROM identity.users WHERE username IN (?, ?, ?))", username, inspectorUsername, otherUsername);
        jdbc.update("DELETE FROM identity.vehicles WHERE user_id IN (SELECT id FROM identity.users WHERE username IN (?, ?, ?))", username, inspectorUsername, otherUsername);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (SELECT id FROM identity.users WHERE username IN (?, ?, ?))", username, inspectorUsername, otherUsername);
        jdbc.update("DELETE FROM identity.users WHERE username IN (?, ?, ?)", username, inspectorUsername, otherUsername);
    }

    @BeforeEach
    void setUp() {
        username = "vehicle.test." + UUID.randomUUID();
        inspectorUsername = "vehicle.inspector." + UUID.randomUUID();
        userId = insertUser(username, "CITIZEN");
        insertUser(inspectorUsername, "INSPECTOR");
        otherUsername = "vehicle.other." + UUID.randomUUID();
        otherUserId = insertUser(otherUsername, "CITIZEN");
    }

    private UUID insertUser(String username, String role) {
        OffsetDateTime now = OffsetDateTime.now();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFirstName("Vehicle");
        user.setLastName("Test");
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        UUID id = userRepository.save(user).getId();
        UUID roleId = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = ?", UUID.class, role);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", id, roleId);
        return id;
    }

    private String uniquePlate() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private ResponseEntity<String> request(String username, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, PASSWORD);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void shouldCreateVehicleForAuthenticatedOwner() {
        String plate = uniquePlate();
        Map<String, Object> body = Map.of("userId", userId, "plate", plate, "brand", "Toyota",
                "model", "Corolla", "color", "Blanco");
        ResponseEntity<String> response = request(username, HttpMethod.POST, "/api/v1/vehicles", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains(plate);
    }

    @Test
    void citizenCannotCreateVehicleForAnotherUser() {
        Map<String, Object> body = Map.of("userId", UUID.randomUUID(), "plate", uniquePlate());
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldRejectVehicleWhenRequestNamesDifferentNonexistentOwner() {
        Map<String, Object> body = Map.of("userId", UUID.randomUUID(), "plate", uniquePlate());
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldRejectDuplicatePlate() {
        Map<String, Object> body = Map.of("userId", userId, "plate", uniquePlate(), "brand", "Toyota");
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldRejectBlankPlate() {
        Map<String, Object> body = Map.of("userId", userId, "plate", "");
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldRejectPlateLongerThanTenCharacters() {
        Map<String, Object> body = Map.of("userId", userId, "plate", "ABC123456789");
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldCreateVehicleWithoutOptionalFields() {
        Map<String, Object> body = Map.of("userId", userId, "plate", uniquePlate());
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", body).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldFindVehiclesByOwnUser() {
        String plate = uniquePlate();
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", plate))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> response = request(username, HttpMethod.GET, "/api/v1/vehicles/user/" + userId, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(plate);
    }

    @Test
    void citizenCannotListVehiclesBelongingToAnotherUser() {
        assertThat(request(username, HttpMethod.GET, "/api/v1/vehicles/user/" + UUID.randomUUID(), null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void inspectorCanFindVehicleByPlate() {
        String plate = uniquePlate();
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", plate))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(request(inspectorUsername, HttpMethod.GET, "/api/v1/vehicles/plate/" + plate, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldReturnNotFoundWhenPlateDoesNotExist() {
        assertThat(request(inspectorUsername, HttpMethod.GET, "/api/v1/vehicles/plate/DOESNOT", null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldRequireAuthenticationToCreateParkingSession() {
        Map<String, Object> body = Map.of("parkingSpaceQrCode", "QR-TEST-001", "vehicleId", UUID.randomUUID(),
                "tariffId", UUID.randomUUID(), "durationMinutes", 60);
        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/v1/parking/sessions"), body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"lower", "mixed", "trim"})
    void normalizesPlateAndRejectsSameOwnerCaseDuplicate(String variant) {
        String canonical = uniquePlate();
        String input = variant.equals("mixed") ? canonical.substring(0, 1) + canonical.substring(1).toLowerCase(java.util.Locale.ROOT)
                : variant.equals("trim") ? " " + canonical.substring(0, 7).toLowerCase(java.util.Locale.ROOT) + " " : canonical.toLowerCase(java.util.Locale.ROOT);
        canonical = input.trim().toUpperCase(java.util.Locale.ROOT);
        var created = request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", input));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT plate FROM identity.vehicles WHERE user_id = ?", String.class, userId)).isEqualTo(canonical);
        assertThat(request(inspectorUsername, HttpMethod.GET, "/api/v1/vehicles/plate/" + canonical.toLowerCase(java.util.Locale.ROOT), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", canonical.toLowerCase(java.util.Locale.ROOT))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void ownersCanSharePlateAndDeactivationIsIndependentAndIdempotent() {
        String plate = uniquePlate();
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", plate)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(request(otherUsername, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", otherUserId, "plate", plate.toLowerCase(java.util.Locale.ROOT))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = jdbc.queryForObject("SELECT id FROM identity.vehicles WHERE user_id = ?", UUID.class, userId);
        String path = "/api/v1/vehicles/" + id + "/deactivation";
        assertThat(request(otherUsername, HttpMethod.PUT, path, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(inspectorUsername, HttpMethod.PUT, path, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange(url(path), HttpMethod.PUT, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(username, HttpMethod.PUT, path, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var updated = jdbc.queryForObject("SELECT updated_at FROM identity.vehicles WHERE id=?", OffsetDateTime.class, id);
        assertThat(request(username, HttpMethod.PUT, path, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM identity.vehicles WHERE id=?", OffsetDateTime.class, id)).isEqualTo(updated);
        assertThat(jdbc.queryForObject("SELECT active FROM identity.vehicles WHERE user_id=?", Boolean.class, otherUserId)).isTrue();
        assertThat(request(username, HttpMethod.GET, "/api/v1/vehicles/user/" + userId, null).getBody()).isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.vehicles WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", plate)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.vehicles WHERE user_id=?", Integer.class, userId)).isEqualTo(2);
    }

    @Test void mobileBearerCanDeactivateOwnedVehicleAndAmbiguousStaffLookupFailsClosed() throws Exception {
        String plate = uniquePlate();
        assertThat(request(username, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", userId, "plate", plate)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(request(otherUsername, HttpMethod.POST, "/api/v1/vehicles", Map.of("userId", otherUserId, "plate", plate)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(request(inspectorUsername, HttpMethod.GET, "/api/v1/vehicles/plate/" + plate, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        UUID id = jdbc.queryForObject("SELECT id FROM identity.vehicles WHERE user_id=?", UUID.class, userId);
        var login = restTemplate.postForEntity(url("/api/v1/auth/login"), Map.of("username", username, "password", PASSWORD), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(json.readTree(login.getBody()).get("accessToken").asText());
        headers.set("X-Correlation-ID", "vehicle-deactivation-test");
        var result = restTemplate.exchange(url("/api/v1/vehicles/" + id + "/deactivation"), HttpMethod.PUT, new HttpEntity<>(headers), String.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("vehicle-deactivation-test");
        assertThat(json.readTree(result.getBody()).get("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action='VEHICLE_DEACTIVATED' AND resource_id=? AND result='SUCCESS'", Integer.class, id)).isEqualTo(1);
    }

}
