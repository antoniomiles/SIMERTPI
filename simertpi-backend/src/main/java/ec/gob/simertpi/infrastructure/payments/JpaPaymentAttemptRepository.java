package ec.gob.simertpi.infrastructure.payments;

import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaPaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findTopByPaymentIdOrderByAttemptNumberDesc(UUID paymentId);
}
