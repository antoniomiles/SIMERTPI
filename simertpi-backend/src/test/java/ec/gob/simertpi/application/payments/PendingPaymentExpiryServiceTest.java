package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;
import ec.gob.simertpi.domain.payments.repository.PaymentAttemptRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PendingPaymentExpiryServiceTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final PaymentEventPublisher eventPublisher = mock(PaymentEventPublisher.class);
    private final PendingPaymentExpiryService service =
            new PendingPaymentExpiryService(payments, sessions, attempts, eventPublisher);

    @Test
    void cancelsPendingPaymentAndReleasesItsSessionOnceTimeoutHasPassed() {
        OffsetDateTime now = OffsetDateTime.now();
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setParkingSessionId(UUID.randomUUID());
        payment.setStatus("PENDING");
        payment.setCreatedAt(now.minusMinutes(31));
        payment.setAmount(BigDecimal.ONE);
        ParkingSession session = new ParkingSession();
        session.setId(payment.getParkingSessionId());
        session.setStatus("PENDING_PAYMENT");
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setStatus("PENDING");

        when(payments.findByStatusAndCreatedAtBefore("PENDING", now.minusMinutes(30)))
                .thenReturn(List.of(payment));
        when(sessions.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(attempts.findTopByPaymentIdOrderByAttemptNumberDesc(payment.getId()))
                .thenReturn(Optional.of(attempt));

        assertEquals(1, service.cancelExpired(Duration.ofMinutes(30), now));
        assertEquals("CANCELLED", payment.getStatus());
        assertEquals("CANCELLED", session.getStatus());
        assertEquals("CANCELLED", attempt.getStatus());
        verify(eventPublisher).publish(payment, "PAYMENT_CANCELLED_TIMEOUT");
        verify(payments).save(payment);
        verify(sessions).save(session);
    }

    @Test
    void requiresExplicitPositiveTimeoutConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> service.cancelExpired(Duration.ZERO, OffsetDateTime.now()));
        verify(payments, never()).findByStatusAndCreatedAtBefore(any(), any());
    }
}
