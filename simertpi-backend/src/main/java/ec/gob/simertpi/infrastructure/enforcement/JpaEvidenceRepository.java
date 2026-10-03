package ec.gob.simertpi.infrastructure.enforcement;

import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaEvidenceRepository extends JpaRepository<Evidence, UUID> {

    List<Evidence> findByViolationId(UUID violationId);

    Optional<Evidence> findByViolationIdAndStorageKeyAndSha256Hash(
            UUID violationId,
            String storageKey,
            String sha256Hash
    );
}