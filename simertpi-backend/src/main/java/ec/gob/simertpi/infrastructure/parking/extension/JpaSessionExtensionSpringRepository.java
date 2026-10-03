package ec.gob.simertpi.infrastructure.parking.extension;

import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaSessionExtensionSpringRepository
        extends JpaRepository<SessionExtension, UUID> {

    Optional<SessionExtension> findByPaymentId(UUID paymentId);

    List<SessionExtension> findByParkingSessionId(UUID parkingSessionId);

    Optional<SessionExtension> findByParkingSessionIdAndStatus(
            UUID parkingSessionId,
            String status
    );
}
