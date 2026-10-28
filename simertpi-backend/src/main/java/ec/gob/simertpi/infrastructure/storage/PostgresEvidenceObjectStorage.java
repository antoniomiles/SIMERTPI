package ec.gob.simertpi.infrastructure.storage;

import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PostgreSQL-backed evidence content; all mutations join the caller's Spring transaction. */
@Component
public class PostgresEvidenceObjectStorage implements ObjectStorage {
    private static final Pattern KEY = Pattern.compile(
            "^violations/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-f]{64})\\.(jpg|png|pdf)$");
    private final JdbcTemplate jdbc;
    private final EvidenceStorageProperties properties;
    private final LocalObjectStorage legacyLocal;

    public PostgresEvidenceObjectStorage(JdbcTemplate jdbc, EvidenceStorageProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.legacyLocal = new LocalObjectStorage(properties);
    }

    @Override
    public boolean putIfAbsent(String key, byte[] content, String contentType) {
        return putIfAbsent(key, null, content, contentType);
    }

    private boolean putIfAbsent(String key, UUID evidenceId, byte[] content, String contentType) {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        Matcher matcher = matcher(key);
        if (content == null || content.length == 0) throw new InvalidRequestException("Evidence file is empty");
        String hash = sha256(content);
        if (!hash.equals(matcher.group(2))) throw new InvalidRequestException("Evidence storage key does not match its content");
        String extension = matcher.group(3);
        String expectedType = switch (extension) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            default -> throw new InvalidRequestException("Invalid storage key");
        };
        if (!expectedType.equals(contentType)) throw new InvalidRequestException("Evidence content type does not match its storage key");
        UUID violationId = UUID.fromString(matcher.group(1));
        return jdbc.update("""
                INSERT INTO enforcement.evidence_content
                    (storage_key, violation_id, evidence_id, content, content_type, sha256_hash, file_size)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (storage_key) DO NOTHING
                """, key, violationId, evidenceId, content, contentType, hash, (long) content.length) == 1;
    }

    @Override
    public void associateEvidence(String key, UUID evidenceId) {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        matcher(key);
        int updated = jdbc.update("""
                UPDATE enforcement.evidence_content c
                   SET evidence_id = ?
                 WHERE c.storage_key = ?
                   AND c.evidence_id IS NULL
                """, evidenceId, key);
        if (updated == 0) {
            Boolean alreadyLinked = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM enforcement.evidence_content WHERE storage_key = ? AND evidence_id = ?)
                    """, Boolean.class, key, evidenceId);
            if (!Boolean.TRUE.equals(alreadyLinked)) throw new IllegalStateException("Evidence content could not be linked to metadata");
        }
    }

    @Override
    public StoredObject read(String key) throws IOException {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        matcher(key);
        try {
            return jdbc.query("SELECT content, content_type, file_size FROM enforcement.evidence_content WHERE storage_key = ?",
                    rs -> {
                        if (!rs.next()) throw new ResourceNotFoundException("Evidence content not found");
                        byte[] bytes = rs.getBytes("content");
                        return new StoredObject(new ByteArrayInputStream(bytes), rs.getLong("file_size"), rs.getString("content_type"));
                    }, key);
        } catch (ResourceNotFoundException missingFromDatabase) {
            // Existing metadata may still refer to a file left on a persistent legacy mount.
            return legacyLocal.read(key);
        }
    }

    @Override
    public boolean exists(String key) throws IOException {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        matcher(key);
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM enforcement.evidence_content WHERE storage_key = ?)", Boolean.class, key))
                || legacyLocal.exists(key);
    }

    @Override
    public void delete(String key) throws IOException {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        matcher(key);
        jdbc.update("DELETE FROM enforcement.evidence_content WHERE storage_key = ?", key);
        legacyLocal.delete(key);
    }

    @Override
    public List<String> listKeys() throws IOException {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        LinkedHashSet<String> keys = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT storage_key FROM enforcement.evidence_content ORDER BY storage_key", String.class));
        keys.addAll(legacyLocal.listKeys());
        return keys.stream().sorted().toList();
    }

    @Override
    public boolean usesCallerTransaction() { return true; }

    private Matcher matcher(String key) {
        if (key == null) throw new InvalidRequestException("Invalid storage key");
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) throw new InvalidRequestException("Invalid storage key");
        return matcher;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
