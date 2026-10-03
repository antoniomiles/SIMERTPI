package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentAttemptRepository;
import ec.gob.simertpi.application.idempotency.IdempotencyStore;
import ec.gob.simertpi.application.payments.PaymentEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ParkingSessionRepository parkingSessionRepository;

    @Mock
    private TariffRepository tariffRepository;

    @Mock
    private SessionExtensionRepository sessionExtensionRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private PaymentEventPublisher paymentEventPublisher;

    @Mock private ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;

    @InjectMocks
    private PaymentService service;

    @Test
    void shouldApproveExtensionDuringGracePeriod() {
        OffsetDateTime now = OffsetDateTime.now();

        ParkingSession session = createSession(
                "EXPIRED",
                now.minusHours(1),
                now.minusMinutes(5),
                240
        );

        Payment payment = createPayment(session.getId());
        SessionExtension extension = createExtension(
                session.getId(),
                payment.getId(),
                now.minusMinutes(5),
                now.plusMinutes(25)
        );

        when(paymentRepository.findById(payment.getId()))
                .thenReturn(Optional.of(payment));

        when(parkingSessionRepository.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));

        when(sessionExtensionRepository.findByPaymentId(payment.getId()))
                .thenReturn(Optional.of(extension));

        when(rules.evaluateExtension(eq(session), any(), any())).thenReturn(quote(extension, true, "RULES_RESOLVED"));

        when(sessionExtensionRepository.save(any(SessionExtension.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(parkingSessionRepository.save(any(ParkingSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.approve(
                payment.getId(),
                "TX-EXT-GRACE-001"
        );

        assertEquals("APPROVED", result.getStatus());
        assertEquals("APPROVED", extension.getStatus());
        assertEquals("EXTENDED", session.getStatus());
        assertEquals(
                extension.getNewExpectedEndAt(),
                session.getExpectedEndAt()
        );
        assertEquals(1, session.getExtensionCount());

        verify(sessionExtensionRepository).save(extension);
        verify(parkingSessionRepository).save(session);
        verify(paymentRepository).save(payment);
    }

    @Test
    void shouldRejectExtensionApprovalAfterGracePeriod() {
        OffsetDateTime now = OffsetDateTime.now();

        ParkingSession session = createSession(
                "EXPIRED",
                now.minusHours(1),
                now.minusMinutes(11),
                240
        );

        Payment payment = createPayment(session.getId());

        SessionExtension extension = createExtension(
                session.getId(),
                payment.getId(),
                now.minusMinutes(11),
                now.plusMinutes(19)
        );

        when(paymentRepository.findById(payment.getId()))
                .thenReturn(Optional.of(payment));

        when(parkingSessionRepository.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));

        when(sessionExtensionRepository.findByPaymentId(payment.getId()))
                .thenReturn(Optional.of(extension));

        when(rules.evaluateExtension(eq(session), any(), any())).thenReturn(quote(extension, false, "EXTENSION_GRACE_EXCEEDED"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.approve(
                        payment.getId(),
                        "TX-EXT-LATE-001"
                )
        );

        assertEquals(
                "EXTENSION_GRACE_EXCEEDED",
                exception.getMessage()
        );

        verify(tariffRepository, never()).findById(any(UUID.class));
        verify(sessionExtensionRepository, never())
                .save(any(SessionExtension.class));
        verify(parkingSessionRepository, never())
                .save(any(ParkingSession.class));
        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void shouldRejectExtensionApprovalThatExceedsMaximumContinuousTime() {
        OffsetDateTime now = OffsetDateTime.now();

        ParkingSession session = createSession(
                "ACTIVE",
                now.minusHours(3).minusMinutes(30),
                now.plusMinutes(30),
                240
        );

        Payment payment = createPayment(session.getId());

        SessionExtension extension = createExtension(
                session.getId(),
                payment.getId(),
                now,
                now.plusMinutes(90)
        );

        when(paymentRepository.findById(payment.getId()))
                .thenReturn(Optional.of(payment));

        when(parkingSessionRepository.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));

        when(sessionExtensionRepository.findByPaymentId(payment.getId()))
                .thenReturn(Optional.of(extension));

        when(rules.evaluateExtension(eq(session), any(), any())).thenReturn(quote(extension, false, "MAX_CONTINUOUS_EXCEEDED"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.approve(
                        payment.getId(),
                        "TX-EXT-MAX-001"
                )
        );

        assertEquals(
                "MAX_CONTINUOUS_EXCEEDED",
                exception.getMessage()
        );

        verify(sessionExtensionRepository, never())
                .save(any(SessionExtension.class));
        verify(parkingSessionRepository, never())
                .save(any(ParkingSession.class));
        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void shouldRejectOutOfOrderAttemptToApproveFailedPayment() {
        Payment failedPayment = createPayment(UUID.randomUUID());
        failedPayment.setStatus("FAILED");
        when(paymentRepository.findById(failedPayment.getId()))
                .thenReturn(Optional.of(failedPayment));

        assertThrows(IllegalArgumentException.class,
                () -> service.approve(failedPayment.getId(), "LATE-APPROVAL"));

        verify(parkingSessionRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    private ec.gob.simertpi.application.parking.rules.ParkingRulesResult quote(SessionExtension extension, boolean allowed, String reason) {
        return new ec.gob.simertpi.application.parking.rules.ParkingRulesResult(null, null,
                java.time.Instant.now().atZone(java.time.ZoneOffset.UTC), allowed, allowed, false, null,
                "TEST", 30, 240, 10, "USD", BigDecimal.ONE, 60, null, extension.getAdditionalMinutes(), null,
                extension.getNewExpectedEndAt().toInstant(), allowed, reason, null);
    }

    private ParkingSession createSession(
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime expectedEndAt,
            int maxContinuousMinutes
    ) {
        ParkingSession session = new ParkingSession();

        session.setId(UUID.randomUUID());
        session.setUserId(UUID.randomUUID());
        session.setVehicleId(UUID.randomUUID());
        session.setParkingSpaceId(UUID.randomUUID());
        session.setTariffId(UUID.randomUUID());
        session.setStartedAt(startedAt);
        session.setExpectedEndAt(expectedEndAt);
        session.setStatus(status);
        session.setExtensionCount(0);
        session.setTotalAmount(BigDecimal.ZERO);

        return session;
    }

    private Tariff createTariff(int maxContinuousMinutes) {
        Tariff tariff = new Tariff();

        tariff.setId(UUID.randomUUID());
        tariff.setCode("TARIFA-TEST-PAYMENT");
        tariff.setName("Tarifa de prueba");
        tariff.setAmount(BigDecimal.ONE);
        tariff.setDurationMinutes(60);
        tariff.setMinMinutes(30);
        tariff.setMaxContinuousMinutes(maxContinuousMinutes);
        tariff.setActive(true);
        tariff.setValidFrom(OffsetDateTime.now().minusDays(1));
        tariff.setCreatedAt(OffsetDateTime.now());
        tariff.setUpdatedAt(OffsetDateTime.now());

        return tariff;
    }

    private Payment createPayment(UUID sessionId) {
        Payment payment = new Payment();

        payment.setId(UUID.randomUUID());
        payment.setParkingSessionId(sessionId);
        payment.setProvider("TEST_PROVIDER");
        payment.setPaymentMethod("TEST_METHOD");
        payment.setAmount(BigDecimal.ONE);
        payment.setCurrency("USD");
        payment.setStatus("PENDING");
        payment.setIdempotencyKey("PAYMENT-TEST-001");
        payment.setCreatedAt(OffsetDateTime.now());
        payment.setUpdatedAt(OffsetDateTime.now());

        return payment;
    }

    private SessionExtension createExtension(
            UUID sessionId,
            UUID paymentId,
            OffsetDateTime previousEnd,
            OffsetDateTime newEnd
    ) {
        SessionExtension extension = new SessionExtension();

        extension.setId(UUID.randomUUID());
        extension.setParkingSessionId(sessionId);
        extension.setPaymentId(paymentId);
        extension.setAdditionalMinutes(30);
        extension.setPreviousExpectedEndAt(previousEnd);
        extension.setNewExpectedEndAt(newEnd);
        extension.setAmount(BigDecimal.ONE);
        extension.setStatus("PENDING_PAYMENT");
        extension.setCreatedAt(OffsetDateTime.now());
        extension.setUpdatedAt(OffsetDateTime.now());

        return extension;
    }
}
