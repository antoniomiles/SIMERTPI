package ec.gob.simertpi.infrastructure.storage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves that V49 is an additive upgrade for metadata already present at V48. */
class EvidenceV49UpgradeIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15")
            .withDatabaseName("simertpi_v49_upgrade")
            .withUsername("qa")
            .withPassword("qa-test-only");

    private static final UUID inspectorId = UUID.randomUUID();
    private static final UUID zoneId = UUID.randomUUID();
    private static final UUID streetId = UUID.randomUUID();
    private static final UUID spaceId = UUID.randomUUID();
    private static final UUID inspectionId = UUID.randomUUID();
    private static final UUID violationId = UUID.randomUUID();
    private static final UUID oldEvidenceId = UUID.randomUUID();

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareV48AndSeedHistoricalEvidence() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("48"))
                .load()
                .migrate();

        jdbc.update("INSERT INTO identity.users(id, username, email, first_name, last_name) VALUES (?, ?, ?, 'Legacy', 'Inspector')",
                inspectorId, "v49-" + inspectorId, inspectorId + "@example.invalid");
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, 'V49 upgrade test zone')",
                zoneId, "V49-" + inspectorId);
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, 'V49 upgrade test street')",
                streetId, zoneId, "V49-" + inspectorId);
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number) VALUES (?, ?, ?, ?, '01')",
                spaceId, streetId, "V49-" + inspectorId, "V49-QR-" + inspectorId);
        jdbc.update("INSERT INTO enforcement.inspections(id, inspector_id, parking_space_id, observed_at, result) VALUES (?, ?, ?, ?, 'VALID')",
                inspectionId, inspectorId, spaceId, OffsetDateTime.now());
        jdbc.update("INSERT INTO enforcement.violations(id, inspection_id, inspector_id, parking_space_id, violation_type, occurred_at) VALUES (?, ?, ?, ?, 'TEST_ONLY', ?)",
                violationId, inspectionId, inspectorId, spaceId, OffsetDateTime.now());

        byte[] historicalHashMaterial = "legacy-evidence-hash-material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String historicalHash = sha256(historicalHashMaterial);
        jdbc.update("""
                INSERT INTO enforcement.evidence
                    (id, violation_id, evidence_type, storage_key, original_filename, content_type,
                     file_size, sha256_hash, captured_at)
                VALUES (?, ?, 'PHOTO', ?, 'legacy.jpg', 'image/jpeg', ?, ?, CURRENT_TIMESTAMP)
                """, oldEvidenceId, violationId,
                "legacy/" + historicalHash + ".jpg", historicalHashMaterial.length, historicalHash);
        jdbc.update("""
                INSERT INTO audit.functional_audit_log
                    (id, actor_id, actor_name, actor_role, action, resource_type, resource_id, result, metadata)
                VALUES (?, ?, 'legacy-inspector', 'INSPECTOR', 'EVIDENCE_CREATED', 'EVIDENCE', ?, 'SUCCESS', '{}'::jsonb)
                """, UUID.randomUUID(), inspectorId, oldEvidenceId);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Test
    void v49PreservesV48EvidenceAndAuditThenAcceptsNewByteaEvidence() throws Exception {
        assertThat(jdbc.queryForObject("SELECT max(version::integer) FROM public.flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(48);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM enforcement.evidence WHERE id = ?", Long.class, oldEvidenceId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE resource_id = ? AND action = 'EVIDENCE_CREATED'", Long.class, oldEvidenceId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enforcement.evidence_content WHERE evidence_id = ?", Long.class, oldEvidenceId))
                .isZero();

        UUID newEvidenceId = UUID.randomUUID();
        byte[] bytes = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x31, 0x32};
        String digest = sha256(bytes);
        String storageKey = "violations/" + violationId + "/" + digest + ".jpg";
        jdbc.update("""
                INSERT INTO enforcement.evidence
                    (id, violation_id, evidence_type, storage_key, original_filename, content_type,
                     file_size, sha256_hash, captured_at)
                VALUES (?, ?, 'PHOTO', ?, 'new.jpg', 'image/jpeg', ?, ?, CURRENT_TIMESTAMP)
                """, newEvidenceId, violationId, storageKey, bytes.length, digest);
        jdbc.update("""
                INSERT INTO enforcement.evidence_content
                    (storage_key, violation_id, evidence_id, content, content_type, sha256_hash, file_size)
                VALUES (?, ?, ?, ?, 'image/jpeg', ?, ?)
                """, storageKey, violationId, newEvidenceId, bytes, digest, bytes.length);

        assertThat(jdbc.queryForObject("SELECT content FROM enforcement.evidence_content WHERE evidence_id = ?", byte[].class, newEvidenceId))
                .containsExactly(bytes);
        assertThat(jdbc.queryForObject("SELECT sha256_hash FROM enforcement.evidence_content WHERE evidence_id = ?", String.class, newEvidenceId))
                .isEqualTo(digest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enforcement.evidence WHERE id IN (?, ?)", Long.class, oldEvidenceId, newEvidenceId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT max(version::integer) FROM public.flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(53);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
