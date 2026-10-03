package ec.gob.simertpi.infrastructure.payments;

import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

@Repository
public class PaymentPersistenceAdapter implements PaymentRepository {

    private final JpaPaymentRepository jpaPaymentRepository;

    public PaymentPersistenceAdapter(JpaPaymentRepository jpaPaymentRepository) {
        this.jpaPaymentRepository = jpaPaymentRepository;
    }

    @Override
    public Payment save(Payment payment) {
        return jpaPaymentRepository.save(payment);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpaPaymentRepository.findById(id);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return jpaPaymentRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<Payment> findByParkingSessionId(UUID parkingSessionId) {
        return jpaPaymentRepository.findByParkingSessionId(parkingSessionId);
    }

    @Override
    public Optional<Payment> findByProviderTransactionId(String providerTransactionId) {
        return jpaPaymentRepository.findByProviderTransactionId(providerTransactionId);
    }

    @Override
    public List<Payment> findByStatusAndCreatedAtBefore(String status, OffsetDateTime cutoff) {
        return jpaPaymentRepository.findByStatusAndCreatedAtBefore(status, cutoff);
    }
}
