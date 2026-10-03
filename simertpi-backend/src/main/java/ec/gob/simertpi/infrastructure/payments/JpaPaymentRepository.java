package ec.gob.simertpi.infrastructure.payments;

import ec.gob.simertpi.domain.payments.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface JpaPaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByParkingSessionId(UUID parkingSessionId);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    List<Payment> findByStatusAndCreatedAtBefore(String status, OffsetDateTime cutoff);
}
