package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import ec.gob.simertpi.domain.enforcement.entity.Violation;
import ec.gob.simertpi.domain.enforcement.repository.EvidenceRepository;
import ec.gob.simertpi.domain.enforcement.repository.ViolationRepository;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import ec.gob.simertpi.infrastructure.storage.EvidenceStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class EvidenceService {
    @Autowired(required=false) private ec.gob.simertpi.application.operations.OperationalMetrics metrics;
    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private final EvidenceRepository evidenceRepository;
    private final ViolationRepository violations;
    private final InspectorAuthorizationService authorization;
    private final ec.gob.simertpi.application.audit.AuditService audit;
    private final ObjectStorage storage;
    private final EvidenceStorageProperties storageProperties;

    @Autowired
    public EvidenceService(EvidenceRepository evidenceRepository, ViolationRepository violations,
                           InspectorAuthorizationService authorization, ObjectStorage storage,
                           EvidenceStorageProperties storageProperties, ec.gob.simertpi.application.audit.AuditService audit) {
        this.evidenceRepository = evidenceRepository;
        this.violations = violations;
        this.authorization = authorization;
        this.audit = audit;
        this.storage = storage;
        this.storageProperties = storageProperties;
    }

    public ValidatedFile validateUpload(String originalFilename, String declaredContentType, byte[] content) {
        if (storageProperties == null || !storageProperties.isEnabled()) {
            throw new InvalidRequestException("Evidence upload is disabled");
        }
        if (content == null || content.length == 0) throw new InvalidRequestException("Evidence file is empty");
        if (content.length > storageProperties.getMaxFileSizeBytes()) {
            throw new InvalidRequestException("Evidence file exceeds the configured size limit");
        }
        String filename = sanitizeFilename(originalFilename);
        String extension = extension(filename);
        String detected = detectContentType(content);
        if (detected == null || !storageProperties.getAllowedMediaTypes().contains(detected)
                || !detected.equalsIgnoreCase(declaredContentType == null ? "" : declaredContentType.trim())
                || !extensionMatches(extension, detected)) {
            throw new InvalidRequestException("Evidence file type is not allowed or does not match its content");
        }
        String type = detected.equals("application/pdf") ? "DOCUMENT" : "PHOTO";
        return new ValidatedFile(content.clone(), filename, extension, detected, type,
                sha256(content), content.length);
    }

    public Evidence upload(String username, UUID violationId, ValidatedFile file, OffsetDateTime capturedAt,
                          BigDecimal latitude, BigDecimal longitude) {
        file = validateUpload(file.filename(), file.contentType(), file.content());
        User inspector = authorization.requireInspector(username);
        Violation violation = violations.findById(violationId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
        requireOwner(violation, inspector);
        if (storage == null) throw new IllegalStateException("Evidence storage is unavailable");
        OffsetDateTime captured = capturedAt == null ? OffsetDateTime.now() : capturedAt;
        evidenceRepository.lockViolation(violationId);
        String key = "violations/" + violationId + "/" + file.sha256() + "." + file.extension();
        var existing = evidenceRepository.findByViolationIdAndStorageKeyAndSha256Hash(
                violationId, key, file.sha256());
        if (existing.isPresent()) return existing.get();

        try {
            if (!storage.putIfAbsent(key, file.content(), file.contentType())) {
                return evidenceRepository.findByViolationIdAndStorageKeyAndSha256Hash(
                                violationId, key, file.sha256())
                        .orElseThrow(() -> new IllegalArgumentException("Evidence already exists"));
            }
        } catch (IOException storageFailure) {
            throw new IllegalStateException("Evidence storage operation failed", storageFailure);
        }
        registerRollbackCleanup(key);

        Evidence evidence = new Evidence();
        evidence.setId(UUID.randomUUID());
        evidence.setViolationId(violationId);
        evidence.setEvidenceType(file.evidenceType());
        evidence.setStorageKey(key);
        evidence.setOriginalFilename(file.filename());
        evidence.setContentType(file.contentType());
        evidence.setFileSize(file.size());
        evidence.setSha256Hash(file.sha256());
        evidence.setCapturedAt(captured);
        evidence.setLatitude(latitude);
        evidence.setLongitude(longitude);
        evidence.setCreatedAt(OffsetDateTime.now());
        try {
            Evidence saved = evidenceRepository.save(evidence);
            audit.success("EVIDENCE_CREATED", "EVIDENCE", saved.getId(), null,
                    java.util.Map.of("violationId", violationId, "contentType", file.contentType(), "fileSize", file.size()));
            return saved;
        } catch (RuntimeException failure) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) deleteQuietly(key);
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public Evidence findById(String username, UUID id) {
        Evidence evidence = evidenceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found"));
        Violation violation = violations.findById(evidence.getViolationId())
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
        authorization.requireEvidenceAccess(username, violation.getInspectorId());
        return evidence;
    }

    @Transactional(readOnly = true)
    @ec.gob.simertpi.application.audit.Audited(action = "EVIDENCE_DOWNLOADED", resourceType = "EVIDENCE", resourceIdArgument = 1)
    public EvidenceDownload download(String username, UUID id) {
        Evidence evidence = evidenceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found"));
        Violation violation = violations.findById(evidence.getViolationId())
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
        authorization.requireEvidenceAccess(username, violation.getInspectorId());
        try {
            try (var object = storage.read(evidence.getStorageKey())) {
                int limit = (int) Math.min(storageProperties.getMaxFileSizeBytes(), Integer.MAX_VALUE - 1L);
                byte[] bytes = object.stream().readNBytes(limit + 1);
                if (bytes.length > limit || evidence.getFileSize() == null || bytes.length != evidence.getFileSize()
                        || !sha256(bytes).equals(evidence.getSha256Hash())) {
                    if (metrics!=null) metrics.event(ec.gob.simertpi.application.operations.OperationalMetrics.Event.EVIDENCE_INTEGRITY_FAILURE);
                    throw new IllegalStateException("Evidence content integrity verification failed");
                }
                return new EvidenceDownload(evidence, new ObjectStorage.StoredObject(
                        new java.io.ByteArrayInputStream(bytes), bytes.length, evidence.getContentType()));
            }
        } catch (IOException storageFailure) {
            throw new IllegalStateException("Evidence storage operation failed", storageFailure);
        }
    }

    @Transactional(readOnly = true)
    public List<Evidence> findByViolationId(String username, UUID violationId) {
        Violation violation = violations.findById(violationId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
        authorization.requireEvidenceAccess(username, violation.getInspectorId());
        return evidenceRepository.findByViolationId(violationId);
    }

    private void registerRollbackCleanup(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) deleteQuietly(key);
                else if (status == STATUS_UNKNOWN) log.warn("event=evidence_transaction result=UNKNOWN");
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException | RuntimeException cleanupFailure) {
            log.warn("event=evidence_compensation result=FAILED");
        }
    }

    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= PNG_MAGIC.length && java.util.Arrays.equals(
                java.util.Arrays.copyOf(bytes, PNG_MAGIC.length), PNG_MAGIC)) return "image/png";
        if (bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            return "application/pdf";
        }
        return null;
    }

    private String sanitizeFilename(String input) {
        if (input == null || input.isBlank()) throw new InvalidRequestException("Original filename is required");
        String basename = input.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (basename.isBlank() || basename.length() > 255) throw new InvalidRequestException("Invalid original filename");
        return basename;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 1 || dot == filename.length() - 1) throw new InvalidRequestException("Unsupported file extension");
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("jpg", "jpeg", "png", "pdf").contains(extension)) {
            throw new InvalidRequestException("Unsupported file extension");
        }
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private boolean extensionMatches(String extension, String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> "jpg".equals(extension) || "jpeg".equals(extension);
            case "image/png" -> "png".equals(extension);
            case "application/pdf" -> "pdf".equals(extension);
            default -> false;
        };
    }

    private String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void requireOwner(Violation violation, User inspector) {
        if (!inspector.getId().equals(violation.getInspectorId())) throw new ForbiddenException();
    }

    public record ValidatedFile(byte[] content, String filename, String extension, String contentType,
                                String evidenceType, String sha256, long size) {
        public ValidatedFile { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    public record EvidenceDownload(Evidence evidence, ObjectStorage.StoredObject object) { }
}
