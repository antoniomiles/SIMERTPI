package ec.gob.simertpi.domain.payments.repository;

import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;

import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository {

    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNumberDesc(UUID paymentId);
}
