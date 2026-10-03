package ec.gob.simertpi.infrastructure.payments;

import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;
import ec.gob.simertpi.domain.payments.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentAttemptPersistenceAdapter implements PaymentAttemptRepository {

    private final JpaPaymentAttemptRepository repository;

    public PaymentAttemptPersistenceAdapter(JpaPaymentAttemptRepository repository) {
        this.repository = repository;
    }

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        return repository.save(attempt);
    }

    @Override
    public Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNumberDesc(UUID paymentId) {
        return repository.findTopByPaymentIdOrderByAttemptNumberDesc(paymentId);
    }
}
