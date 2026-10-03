package ec.gob.simertpi.domain.enforcement.repository;

import ec.gob.simertpi.domain.enforcement.entity.Evidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceRepository {

    void lockViolation(UUID violationId);

    Evidence save(Evidence evidence);

    Optional<Evidence> findById(UUID id);

    List<Evidence> findByViolationId(UUID violationId);

    Optional<Evidence> findByViolationIdAndStorageKeyAndSha256Hash(
            UUID violationId,
            String storageKey,
            String sha256Hash
    );
}