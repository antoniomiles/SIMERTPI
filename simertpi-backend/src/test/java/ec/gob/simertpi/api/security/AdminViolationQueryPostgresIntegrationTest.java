package ec.gob.simertpi.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.testsupport.AdminSessionTestSupport;
import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminViolationQueryPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-violation-admin-password-2026";
    private static final OffsetDateTime MUNICIPAL_DAY_START = OffsetDateTime.parse("2026-10-19T05:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired ObjectStorage evidenceStorage;

    private String adminUsername;
    private AdminSessionTestSupport.Session adminSession;

    @BeforeEach
    void loginAdministrator() throws Exception {
        adminUsername = "violation-admin-" + UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                adminId, adminUsername, adminUsername + "@example.invalid", encoder.encode(PASSWORD), "Test", "Administrator");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", adminId);
        adminSession = AdminSessionTestSupport.login(mvc, adminUsername, PASSWORD);
    }

    @Test
    void requiresAdminSessionAndReturnsOnlyMinimalUncachedFields() throws Exception {
        Fixture fixture = fixture("OPEN", "2026-10-19T05:00:00Z", "MINIMAL");
        mvc.perform(get("/api/v1/admin/violations").secure(true))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/violations").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .header("Origin", "https://untrusted.invalid"))
                .andExpect(status().isForbidden());

        MvcResult result = mvc.perform(get("/api/v1/admin/violations").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("plate", fixture.plate()).param("zone", fixture.zoneCode())
                        .param("status", "open").param("from", "2026-10-19").param("to", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("totalElements").asLong()).isEqualTo(1L);
        JsonNode row = response.path("items").get(0);
        assertThat(row.path("id").asText()).isEqualTo(fixture.violationId().toString());
        assertThat(row.path("plate").asText()).isEqualTo(fixture.plate());
        assertThat(row.path("status").asText()).isEqualTo("OPEN");
        assertThat(row.path("zoneCode").asText()).isEqualTo(fixture.zoneCode());
        assertThat(row.path("inspectorUsername").asText()).isEqualTo(fixture.inspectorUsername());
        assertThat(row.path("evidenceCount").asLong()).isEqualTo(1L);
        for (String excluded : new String[]{"fineAmount", "description", "ownerEmail", "email", "vehicleId",
                "inspectorId", "latitude", "longitude", "storageKey", "sha256Hash", "originalFilename", "contentType"}) {
            assertThat(row.has(excluded)).as("response excludes %s", excluded).isFalse();
        }

        MvcResult detail = mvc.perform(get("/api/v1/admin/violations/" + fixture.violationId()).secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        JsonNode detailJson = json.readTree(detail.getResponse().getContentAsString());
        assertThat(detailJson.path("id").asText()).isEqualTo(fixture.violationId().toString());
        assertThat(detailJson.has("fineAmount")).isFalse();
        assertThat(detailJson.has("description")).isFalse();

        assertThat(countAudit("ADMIN_VIOLATIONS_SEARCHED")).isEqualTo(1L);
        assertThat(countAudit("ADMIN_VIOLATION_VIEWED")).isEqualTo(1L);
    }

    @Test
    void inspectorCannotReadAdministrativeViolationQueries() throws Exception {
        String username = "violation-reader-" + UUID.randomUUID();
        UUID inspectorId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                inspectorId, username, username + "@example.invalid", encoder.encode(PASSWORD), "Test", "Inspector");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='INSPECTOR'", inspectorId);
        MvcResult csrf = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        var csrfCookie = csrf.getResponse().getCookie("__Host-SIMERTPI_ADMIN_XSRF");
        mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/violations").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                .andExpect(status().isOk());
    }

    @Test
    void administratorCanReviewMinimalEvidenceMetadataAndDownloadAuditedContent() throws Exception {
        Fixture fixture = fixture("OPEN", "2026-10-19T05:00:00Z", "EVIDENCE");
        UUID evidenceId = jdbc.queryForObject(
                "SELECT id FROM enforcement.evidence WHERE violation_id=?", UUID.class, fixture.violationId());
        byte[] content = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        String key = "violations/" + fixture.violationId() + "/" + hash + ".png";
        try {
            assertThat(evidenceStorage.putIfAbsent(key, content, "image/png")).isTrue();
            jdbc.update("UPDATE enforcement.evidence SET storage_key=?, original_filename='proof.png', content_type='image/png', file_size=?, sha256_hash=? WHERE id=?",
                    key, content.length, hash, evidenceId);

            mvc.perform(get("/api/v1/admin/violations/" + fixture.violationId() + "/evidence").secure(true))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/violations/" + fixture.violationId() + "/evidence").secure(true)
                            .with(user(fixture.inspectorUsername()).authorities(
                                    new org.springframework.security.core.authority.SimpleGrantedAuthority("INSPECTOR"))))
                    .andExpect(status().isUnauthorized());

            MvcResult metadata = mvc.perform(get("/api/v1/admin/violations/" + fixture.violationId() + "/evidence").secure(true)
                            .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andReturn();
            JsonNode item = json.readTree(metadata.getResponse().getContentAsString()).get(0);
            assertThat(item.path("id").asText()).isEqualTo(evidenceId.toString());
            assertThat(item.path("violationId").asText()).isEqualTo(fixture.violationId().toString());
            assertThat(item.path("evidenceType").asText()).isEqualTo("PHOTO");
            for (String excluded : new String[]{"storageKey", "sha256Hash", "originalFilename"}) {
                assertThat(item.has(excluded)).as("metadata excludes %s", excluded).isFalse();
            }
            assertThat(countAudit("EVIDENCE_LISTED")).isEqualTo(1L);

            MvcResult downloaded = mvc.perform(get("/api/v1/admin/evidence/" + evidenceId + "/content").secure(true)
                            .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("proof.png")))
                    .andReturn();
            assertThat(downloaded.getResponse().getContentAsByteArray()).containsExactly(content);
            assertThat(countAudit("EVIDENCE_DOWNLOADED")).isEqualTo(1L);
        } finally {
            evidenceStorage.delete(key);
        }
    }

    @Test
    void administratorCanSearchAndViewInspectionsWithoutCreatingViolations() throws Exception {
        Fixture fixture = fixture("OPEN", "2026-10-19T05:00:00Z", "INSPECTION");
        UUID inspectionId = jdbc.queryForObject("SELECT inspection_id FROM enforcement.violations WHERE id=?",
                UUID.class, fixture.violationId());
        jdbc.update("DELETE FROM enforcement.violations WHERE id=?", fixture.violationId());

        mvc.perform(get("/api/v1/admin/inspections").secure(true))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/inspections").secure(true)
                        .with(user(fixture.inspectorUsername()).authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("INSPECTOR"))))
                .andExpect(status().isUnauthorized());

        MvcResult result = mvc.perform(get("/api/v1/admin/inspections").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("plate", fixture.plate()).param("result", "violation")
                        .param("zone", fixture.zoneCode()).param("from", "2026-10-19").param("to", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        JsonNode page = json.readTree(result.getResponse().getContentAsString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        JsonNode row = page.path("items").get(0);
        assertThat(row.path("id").asText()).isEqualTo(inspectionId.toString());
        assertThat(row.path("plate").asText()).isEqualTo(fixture.plate());
        assertThat(row.path("violationId").isNull()).isTrue();
        assertThat(row.has("notes")).isFalse();
        assertThat(row.has("latitude")).isFalse();
        assertThat(row.has("vehicleId")).isFalse();

        MvcResult detail = mvc.perform(get("/api/v1/admin/inspections/" + inspectionId).secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        JsonNode detailJson = json.readTree(detail.getResponse().getContentAsString());
        assertThat(detailJson.path("id").asText()).isEqualTo(inspectionId.toString());
        assertThat(detailJson.path("notes").asText()).isEqualTo("private inspection note");
        assertThat(detailJson.path("createdAt").asText()).isNotBlank();
        assertThat(detailJson.path("latitude").decimalValue()).isEqualByComparingTo("-3.0");
        assertThat(detailJson.path("longitude").decimalValue()).isEqualByComparingTo("-79.0");
        assertThat(detailJson.path("violationId").isNull()).isTrue();
        for (String excluded : new String[]{"vehicleId", "ownerEmail", "fineAmount", "description", "storageKey"}) {
            assertThat(detailJson.has(excluded)).as("detail excludes %s", excluded).isFalse();
        }
        assertThat(countAudit("ADMIN_INSPECTIONS_SEARCHED")).isEqualTo(1L);
        assertThat(countAudit("ADMIN_INSPECTION_VIEWED")).isEqualTo(1L);

        mvc.perform(post("/api/v1/admin/inspections").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession)))
                .andExpect(status().isForbidden());
    }

    @Test
    void filtersByMunicipalDateStatusPlateAndZoneAndPaginatesInTheDatabase() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        Fixture first = fixture("OPEN", "2026-10-19T05:15:00Z", "PAGE-A-" + marker);
        Fixture second = fixture("NOTIFIED", "2026-10-19T05:30:00Z", "PAGE-B-" + marker);

        MvcResult filtered = mvc.perform(get("/api/v1/admin/violations").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("status", "NOTIFIED").param("plate", second.plate())
                        .param("zone", second.zoneCode()).param("from", "2026-10-19").param("to", "2026-10-19"))
                .andExpect(status().isOk()).andReturn();
        JsonNode filteredPage = json.readTree(filtered.getResponse().getContentAsString());
        assertThat(filteredPage.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(filteredPage.path("items").get(0).path("id").asText()).isEqualTo(second.violationId().toString());

        MvcResult paged = mvc.perform(get("/api/v1/admin/violations").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("q", marker).param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstPage = json.readTree(paged.getResponse().getContentAsString());
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(2L);
        assertThat(firstPage.path("items").size()).isEqualTo(1);
        assertThat(firstPage.path("first").asBoolean()).isTrue();
        assertThat(firstPage.path("last").asBoolean()).isFalse();
        MvcResult next = mvc.perform(get("/api/v1/admin/violations").secure(true)
                        .cookie(AdminSessionTestSupport.sessionCookie(adminSession))
                        .param("q", marker).param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode secondPage = json.readTree(next.getResponse().getContentAsString());
        assertThat(secondPage.path("items").size()).isEqualTo(1);
        assertThat(secondPage.path("last").asBoolean()).isTrue();
        assertThat(firstPage.path("items").get(0).path("id").asText())
                .isNotEqualTo(secondPage.path("items").get(0).path("id").asText());
        assertThat(first.violationId()).isNotEqualTo(second.violationId());
    }

    @Test
    void rejectsInvalidStatusDateRangeAndPaginationAndDoesNotOfferWriteRoutes() throws Exception {
        var cookie = AdminSessionTestSupport.sessionCookie(adminSession);
        mvc.perform(get("/api/v1/admin/violations").secure(true).cookie(cookie).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/violations").secure(true).cookie(cookie).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/violations").secure(true).cookie(cookie)
                        .param("from", "2026-10-20").param("to", "2026-10-19"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/violations").secure(true).cookie(cookie)
                        .param("from", "2025-10-19").param("to", "2026-10-20"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/violations").secure(true)
                        .cookie(cookie).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    private Fixture fixture(String status, String occurredAtText, String suffix) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
        UUID ownerId = UUID.randomUUID();
        UUID inspectorId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID streetId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID inspectionId = UUID.randomUUID();
        UUID violationId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        String ownerName = "violation-owner-" + unique;
        String inspectorUsername = "violation-inspector-" + unique;
        String plate = "Q" + unique;
        String zoneCode = "VI-Z-" + suffix + "-" + unique;
        OffsetDateTime occurredAt = OffsetDateTime.parse(occurredAtText);
        OffsetDateTime createdAt = occurredAt.plusMinutes(1);

        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                ownerId, ownerName, ownerName + "@example.invalid", encoder.encode(PASSWORD), "Private", "Owner");
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                inspectorId, inspectorUsername, inspectorUsername + "@example.invalid", encoder.encode(PASSWORD), "Field", "Inspector");
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicleId, ownerId, plate);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)",
                zoneId, zoneCode, "Test zone " + suffix);
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?,?,TRUE)",
                streetId, zoneId, "VI-ST-" + unique, "Test street " + suffix);
        jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,active) VALUES (?,?,?,?,?,TRUE)",
                spaceId, streetId, "VI-SP-" + unique, "VI-QR-" + unique, "VI-" + unique);
        jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status,total_amount,extension_count,created_at,updated_at) VALUES (?,?,?,?,?,?,'ACTIVE',0,0,?,?)",
                sessionId, ownerId, vehicleId, spaceId, occurredAt.minusMinutes(10), occurredAt.plusMinutes(50), createdAt, createdAt);
        jdbc.update("INSERT INTO enforcement.inspections(id,inspector_id,parking_session_id,parking_space_id,vehicle_id,observed_plate,observed_at,result,notes,latitude,longitude,created_at) VALUES (?,?,?,?,?,?,?,'VIOLATION','private inspection note',-3.0,-79.0,?)",
                inspectionId, inspectorId, sessionId, spaceId, vehicleId, plate, occurredAt, createdAt);
        jdbc.update("INSERT INTO enforcement.violations(id,inspection_id,inspector_id,parking_session_id,parking_space_id,vehicle_id,plate,violation_type,description,occurred_at,status,fine_amount,notified_at,paid_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'ADMIN_TEST','private violation narrative',?,?,0,?,?,?,?)",
                violationId, inspectionId, inspectorId, sessionId, spaceId, vehicleId, plate, occurredAt, status,
                "NOTIFIED".equals(status) ? createdAt : null, "PAID".equals(status) ? createdAt : null, createdAt, createdAt);
        jdbc.update("INSERT INTO enforcement.evidence(id,violation_id,evidence_type,storage_key,original_filename,content_type,file_size,sha256_hash,captured_at,latitude,longitude,created_at) VALUES (?,?, 'PHOTO','private/storage/key','private.jpg','image/jpeg',12,?, ?, -3.0,-79.0,?)",
                evidenceId, violationId, "a".repeat(64), occurredAt, createdAt);
        return new Fixture(violationId, plate, zoneCode, inspectorUsername);
    }

    private long countAudit(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE actor_name=? AND action=? AND result='SUCCESS'",
                Long.class, adminUsername, action);
    }

    private record Fixture(UUID violationId, String plate, String zoneCode, String inspectorUsername) { }
}
