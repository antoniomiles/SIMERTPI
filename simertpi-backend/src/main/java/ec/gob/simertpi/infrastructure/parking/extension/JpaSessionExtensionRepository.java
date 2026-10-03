package ec.gob.simertpi.infrastructure.parking.extension;

import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaSessionExtensionRepository implements SessionExtensionRepository {

    private final JpaSessionExtensionSpringRepository repository;

    public JpaSessionExtensionRepository(JpaSessionExtensionSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    public SessionExtension save(SessionExtension extension) {
        return repository.save(extension);
    }

    @Override
    public Optional<SessionExtension> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<SessionExtension> findByPaymentId(UUID paymentId) {
        return repository.findByPaymentId(paymentId);
    }

    @Override
    public List<SessionExtension> findByParkingSessionId(UUID parkingSessionId) {
        return repository.findByParkingSessionId(parkingSessionId);
    }

    @Override
    public Optional<SessionExtension> findPendingByParkingSessionId(UUID parkingSessionId) {
        return repository.findByParkingSessionIdAndStatus(
                parkingSessionId,
                "PENDING_PAYMENT"
        );
    }
}
