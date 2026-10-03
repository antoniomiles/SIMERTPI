package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.application.audit.Audited;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.api.IdempotencyConflictException;
import ec.gob.simertpi.api.InvalidIdempotencyKeyException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.idempotency.IdempotencyRecord;
import ec.gob.simertpi.application.idempotency.IdempotencyStore;
import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import ec.gob.simertpi.domain.enforcement.entity.Violation;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class EnforcementIdempotencyService {
    private static final int CREATED = 201;
    private static final int TTL_HOURS = 24;
    private final IdempotencyStore store;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    private final InspectionService inspectionService;
    private final ViolationService violationService;
    private final EvidenceService evidenceService;
    private final ec.gob.simertpi.application.audit.AuditService audit;

    public EnforcementIdempotencyService(IdempotencyStore store, UserRepository users, ObjectMapper mapper,
                                         EntityManager entityManager, InspectionService inspectionService,
                                         ViolationService violationService, EvidenceService evidenceService,
                                         ec.gob.simertpi.application.audit.AuditService audit) {
        this.audit = audit;
        this.store = store;
        this.users = users;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.inspectionService = inspectionService;
        this.violationService = violationService;
        this.evidenceService = evidenceService;
    }

    @Transactional
    @Audited(action = "INSPECTION_CREATED", resourceType = "INSPECTION", idempotencyArgument = 1)
    public Inspection createInspection(String username, String key, InspectionPayload payload) {
        return execute(username, key, "CREATE_INSPECTION", payload,
                () -> inspectionService.create(username, payload.sessionId(), payload.spaceId(), payload.vehicleId(),
                        payload.observedPlate(), payload.observedAt(), payload.result(), payload.notes(),
                        payload.latitude(), payload.longitude()),
                Inspection::getId, id -> inspectionService.findById(username, id));
    }

    @Transactional
    @Audited(action = "VIOLATION_CREATED", resourceType = "VIOLATION", idempotencyArgument = 1)
    public Violation createViolation(String username, String key, ViolationPayload payload) {
        return execute(username, key, "CREATE_VIOLATION", payload,
                () -> violationService.create(username, payload.inspectionId(), payload.violationType(),
                        payload.description(), payload.occurredAt()),
                Violation::getId, id -> violationService.findById(username, id));
    }

    @Transactional
    public Evidence createEvidenceUpload(String username, String key, UUID violationId,
                                         String originalFilename, String declaredContentType, byte[] content,
                                         OffsetDateTime capturedAt, java.math.BigDecimal latitude,
                                         java.math.BigDecimal longitude) {
        try {
            EvidenceService.ValidatedFile file = evidenceService.validateUpload(
                    originalFilename, declaredContentType, content);
            EvidenceUploadFingerprint fingerprint = new EvidenceUploadFingerprint(violationId, file.filename(),
                    file.contentType(), file.size(), file.sha256(), capturedAt, latitude, longitude,
                    originalFilename, declaredContentType);
            return execute(username, key, "CREATE_EVIDENCE", fingerprint,
                    () -> evidenceService.upload(username, violationId, file, capturedAt, latitude, longitude),
                    Evidence::getId, id -> evidenceService.findById(username, id));
        } catch (RuntimeException failure) {
            audit.failure("EVIDENCE_UPLOAD_FAILED", "EVIDENCE", null, failure, key);
            throw failure;
        }
    }

    private <T> T execute(String username, String suppliedKey, String operation, Object payload,
                          Supplier<T> create, Function<T, UUID> idOf, Function<UUID, T> load) {
        if (suppliedKey == null || suppliedKey.isBlank() || suppliedKey.length() > 255) {
            throw new InvalidIdempotencyKeyException();
        }
        User user = users.findByUsername(username)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated inspector not found"));
        String key = user.getId() + ":" + operation + ":" + sha256(suppliedKey.trim());
        String requestHash = hashPayload(payload);
        OffsetDateTime now = OffsetDateTime.now();
        if (!store.claim(key, operation, user.getId(), requestHash, now, now.plusHours(TTL_HOURS))) {
            IdempotencyRecord existing = store.find(key);
            if (!user.getId().equals(existing.userId()) || !operation.equals(existing.operationType())
                    || !requestHash.equals(existing.requestHash()) || !"COMPLETED".equals(existing.status())
                    || !Integer.valueOf(CREATED).equals(existing.responseStatus())) {
                throw new IdempotencyConflictException();
            }
            try {
                if (existing.responseBody() == null || existing.responseBody().isBlank()) {
                    throw new IdempotencyConflictException();
                }
                return load.apply(UUID.fromString(existing.responseBody()));
            } catch (IdempotencyConflictException conflict) {
                throw conflict;
            } catch (IllegalArgumentException malformedId) {
                throw new IdempotencyConflictException();
            }
        }
        T result = create.get();
        entityManager.flush();
        store.complete(key, CREATED, idOf.apply(result).toString(), OffsetDateTime.now());
        return result;
    }

    private String hashPayload(Object payload) {
        try {
            return sha256(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to fingerprint enforcement request", serializationFailure);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record InspectionPayload(UUID sessionId, UUID spaceId, UUID vehicleId, String observedPlate,
                                    OffsetDateTime observedAt, String result, String notes,
                                    java.math.BigDecimal latitude, java.math.BigDecimal longitude) { }
    public record ViolationPayload(UUID inspectionId, String violationType, String description,
                                   OffsetDateTime occurredAt) { }
    public record EvidenceUploadFingerprint(UUID violationId, String filename, String contentType,
                                            long fileSize, String sha256Hash, OffsetDateTime capturedAt,
                                            java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                            String originalFilename, String declaredContentType) { }
}
