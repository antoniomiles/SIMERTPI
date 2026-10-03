package ec.gob.simertpi.infrastructure.enforcement;

import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import ec.gob.simertpi.domain.enforcement.repository.EvidenceRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidencePersistenceAdapter implements EvidenceRepository {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final JpaEvidenceRepository repository;

    public EvidencePersistenceAdapter(JpaEvidenceRepository repository) {
        this.repository = repository;
    }

    @Override
    public void lockViolation(UUID violationId) {
        entityManager.find(ec.gob.simertpi.domain.enforcement.entity.Violation.class, violationId,
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Override
    public Evidence save(Evidence evidence) {
        return repository.save(evidence);
    }

    @Override
    public Optional<Evidence> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public List<Evidence> findByViolationId(UUID violationId) {
        return repository.findByViolationId(violationId);
    }

    @Override
    public Optional<Evidence> findByViolationIdAndStorageKeyAndSha256Hash(
            UUID violationId,
            String storageKey,
            String sha256Hash
    ) {
        return repository.findByViolationIdAndStorageKeyAndSha256Hash(
                violationId,
                storageKey,
                sha256Hash
        );
    }
}