package ec.gob.simertpi.domain.payments.repository;

import ec.gob.simertpi.domain.payments.entity.Payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByParkingSessionId(UUID parkingSessionId);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    List<Payment> findByStatusAndCreatedAtBefore(String status, OffsetDateTime cutoff);
}
