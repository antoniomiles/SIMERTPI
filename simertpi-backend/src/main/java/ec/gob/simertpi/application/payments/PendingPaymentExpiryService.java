package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;
import ec.gob.simertpi.domain.payments.entity.PaymentStatus;
import ec.gob.simertpi.domain.payments.repository.PaymentAttemptRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
public class PendingPaymentExpiryService {

    private final PaymentRepository paymentRepository;
    private final ParkingSessionRepository sessionRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentEventPublisher eventPublisher;

    public PendingPaymentExpiryService(PaymentRepository paymentRepository,
                                       ParkingSessionRepository sessionRepository,
                                       PaymentAttemptRepository attemptRepository,
                                       PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    @Audited(action = "PAYMENT_TIMEOUT", resourceType = "PAYMENT")
    public int cancelExpired(Duration timeout, OffsetDateTime now) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("A positive pending payment timeout is required");
        }
        OffsetDateTime cutoff = now.minus(timeout);
        int cancelled = 0;
        for (Payment candidate : paymentRepository.findByStatusAndCreatedAtBefore("PENDING", cutoff)) {
            ParkingSession session = sessionRepository.findByIdForUpdate(candidate.getParkingSessionId())
                    .orElse(null);
            if (session == null || !"PENDING_PAYMENT".equals(session.getStatus())) {
                continue;
            }
            Payment payment = paymentRepository.findById(candidate.getId()).orElse(null);
            if (payment == null || !"PENDING".equals(payment.getStatus())
                    || payment.getCreatedAt().isAfter(cutoff)) {
                continue;
            }
            if (!PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED)) {
                continue;
            }
            payment.setStatus("CANCELLED");
            payment.setFailureReason("Payment attempt timed out");
            payment.setUpdatedAt(now);
            session.setStatus("CANCELLED");
            session.setUpdatedAt(now);
            paymentRepository.save(payment);
            sessionRepository.save(session);
            eventPublisher.publish(payment, "PAYMENT_CANCELLED_TIMEOUT");
            attemptRepository.findTopByPaymentIdOrderByAttemptNumberDesc(payment.getId())
                    .ifPresent(this::cancelAttempt);
            cancelled++;
        }
        return cancelled;
    }

    private void cancelAttempt(PaymentAttempt attempt) {
        attempt.setStatus("CANCELLED");
        attempt.setResponseCode("TIMEOUT");
        attempt.setResponseMessage("Payment attempt timed out");
        attemptRepository.save(attempt);
    }
}
