package ec.gob.simertpi.domain.parking.extension.repository;

import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionExtensionRepository {

    SessionExtension save(SessionExtension extension);

    Optional<SessionExtension> findById(UUID id);

    Optional<SessionExtension> findByPaymentId(UUID paymentId);

    List<SessionExtension> findByParkingSessionId(UUID parkingSessionId);

    Optional<SessionExtension> findPendingByParkingSessionId(UUID parkingSessionId);
}
