package ec.gob.simertpi.infrastructure.storage;

import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PostgresEvidenceObjectStorageIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};

    @Autowired JdbcTemplate jdbc;
    @Autowired PostgresEvidenceObjectStorage storage;
    @Autowired ObjectStorage applicationStorage;
    @Autowired EvidenceStorageProperties storageProperties;
    @Autowired PlatformTransactionManager transactionManager;

    private final UUID userId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID streetId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID inspectionId = UUID.randomUUID();
    private final UUID violationId = UUID.randomUUID();

    @BeforeEach
    void createSyntheticViolation() {
        OffsetDateTime now = OffsetDateTime.now();
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO identity.users(id, username, email, first_name, last_name) VALUES (?, ?, ?, 'Test', 'Inspector')",
                userId, "evidence-" + unique, "evidence-" + unique + "@example.invalid");
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)", zoneId, "EZ-" + unique, "Evidence test zone");
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, ?)", streetId, zoneId, "ES-" + unique, "Evidence test street");
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number) VALUES (?, ?, ?, ?, '01')",
                spaceId, streetId, "EP-" + unique, "EQ-" + unique);
        jdbc.update("INSERT INTO enforcement.inspections(id, inspector_id, parking_space_id, observed_at, result) VALUES (?, ?, ?, ?, 'VALID')",
                inspectionId, userId, spaceId, now);
        jdbc.update("INSERT INTO enforcement.violations(id, inspection_id, inspector_id, parking_space_id, violation_type, occurred_at) VALUES (?, ?, ?, ?, 'TEST_ONLY', ?)",
                violationId, inspectionId, userId, spaceId, now);
    }

    @AfterEach
    void removeSyntheticViolation() {
        jdbc.update("DELETE FROM enforcement.violations WHERE id = ?", violationId);
        jdbc.update("DELETE FROM enforcement.inspections WHERE id = ?", inspectionId);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE id = ?", spaceId);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", streetId);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", zoneId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", userId);
    }

    @Test
    void storesAndReadsBytesLinkedToMetadataWithVerifiedDigest() throws Exception {
        String key = key();
        UUID evidenceId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(storage.putIfAbsent(key, JPEG, "image/jpeg")).isTrue();
            insertMetadata(evidenceId, key, JPEG);
            storage.associateEvidence(key, evidenceId);
        });

        try (var stored = storage.read(key)) {
            byte[] actual = stored.stream().readAllBytes();
            assertThat(actual).containsExactly(JPEG);
            assertThat(stored.size()).isEqualTo(JPEG.length);
            assertThat(stored.contentType()).isEqualTo("image/jpeg");
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)))
                    .isEqualTo(hash());
        }
        assertThat(jdbc.queryForObject("SELECT evidence_id FROM enforcement.evidence_content WHERE storage_key = ?", UUID.class, key))
                .isEqualTo(evidenceId);
        assertThat(jdbc.queryForObject("SELECT file_size = octet_length(content) FROM enforcement.evidence_content WHERE storage_key = ?", Boolean.class, key))
                .isTrue();
        PostgresEvidenceObjectStorage recreatedProvider = new PostgresEvidenceObjectStorage(jdbc, storageProperties);
        try (var afterRestart = recreatedProvider.read(key)) {
            assertThat(afterRestart.stream().readAllBytes()).containsExactly(JPEG);
        }
    }

    @Test
    void duplicateKeyIsIdempotentAndDoesNotOverwriteBytes() throws Exception {
        String key = key();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(storage.putIfAbsent(key, JPEG, "image/jpeg")).isTrue();
            assertThat(storage.putIfAbsent(key, JPEG, "image/jpeg")).isFalse();
        });
        try (var stored = storage.read(key)) { assertThat(stored.stream().readAllBytes()).containsExactly(JPEG); }
        assertThatThrownBy(() -> storage.putIfAbsent(key, new byte[]{1, 2, 3}, "image/jpeg"))
                .isInstanceOf(ec.gob.simertpi.api.InvalidRequestException.class);
    }

    @Test
    void transactionRollbackRemovesBothContentAndMetadata() throws Exception {
        String key = key();
        UUID evidenceId = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(storage.putIfAbsent(key, JPEG, "image/jpeg")).isTrue();
            insertMetadata(evidenceId, key, JPEG);
            storage.associateEvidence(key, evidenceId);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("synthetic rollback");

        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence_content WHERE storage_key = ?)", Boolean.class, key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence WHERE id = ?)", Boolean.class, evidenceId)).isFalse();
    }

    @Test
    void rejectsKeyWhoseContentDigestDoesNotMatch() {
        assertThatThrownBy(() -> storage.putIfAbsent(key(), new byte[]{4, 5, 6}, "image/jpeg"))
                .isInstanceOf(ec.gob.simertpi.api.InvalidRequestException.class);
    }

    @Test
    void violationDeletionCascadesStoredBytesAndMetadata() throws Exception {
        String key = key();
        UUID evidenceId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(storage.putIfAbsent(key, JPEG, "image/jpeg")).isTrue();
            insertMetadata(evidenceId, key, JPEG);
            storage.associateEvidence(key, evidenceId);
        });
        jdbc.update("DELETE FROM enforcement.violations WHERE id = ?", violationId);
        assertThat(storage.exists(key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence WHERE id = ?)", Boolean.class, evidenceId)).isFalse();
    }

    @Test
    void legacyMetadataWithoutPostgresBytesRemainsAndCanUseAvailableLocalFallback() throws Exception {
        String key = key();
        UUID evidenceId = UUID.randomUUID();
        insertMetadata(evidenceId, key, JPEG);
        assertThat(storage.exists(key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence WHERE id = ?)", Boolean.class, evidenceId)).isTrue();

        LocalObjectStorage legacy = new LocalObjectStorage(storageProperties);
        assertThat(legacy.putIfAbsent(key, JPEG, "image/jpeg")).isTrue();
        try (var stored = applicationStorage.read(key)) {
            assertThat(stored.stream().readAllBytes()).containsExactly(JPEG);
        }
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence WHERE id = ?)", Boolean.class, evidenceId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence_content WHERE storage_key = ?)", Boolean.class, key)).isFalse();
    }

    private void insertMetadata(UUID id, String key, byte[] content) {
        jdbc.update("""
                INSERT INTO enforcement.evidence
                    (id, violation_id, evidence_type, storage_key, original_filename, content_type, file_size, sha256_hash, captured_at)
                VALUES (?, ?, 'PHOTO', ?, 'test.jpg', 'image/jpeg', ?, ?, CURRENT_TIMESTAMP)
                """, id, violationId, key, (long) content.length, hash());
    }

    private String key() { return "violations/" + violationId + "/" + hash() + ".jpg"; }
    private String hash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JPEG)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
