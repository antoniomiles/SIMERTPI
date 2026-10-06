package ec.gob.simertpi.application.parking.extension;

import ec.gob.simertpi.application.payments.PaymentService;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParkingSessionExtensionServiceTest {

    @Mock
    private SessionExtensionRepository sessionExtensionRepository;

    @Mock
    private ParkingSessionRepository parkingSessionRepository;

    @Mock
    private ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private ParkingSessionExtensionService service;

    @Test
    void expiredExtensionMustRevalidateRulesBeforePayment() {
        var now = OffsetDateTime.now();
        for (int overdue : java.util.List.of(5,11)) {
            var session=createSession("EXPIRED",now.minusHours(1),now.minusMinutes(overdue));
            when(parkingSessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
            when(rules.evaluateExtension(eq(session),eq(30),any())).thenThrow(new IllegalArgumentException("SESSION_NOT_EXTENSIBLE"));
            assertThrows(IllegalArgumentException.class,()->service.requestExtension(session.getId(),30,
                "TEST_PROVIDER","TEST_METHOD","EXPIRED-"+overdue));
        }
        verifyNoInteractions(paymentService);
        verify(sessionExtensionRepository,never()).save(any(SessionExtension.class));
    }

    @Test
    void shouldRejectExtensionWhenMaximumTimeWasReached() {
        OffsetDateTime now = OffsetDateTime.now();

        ParkingSession session = createSession(
                "MAX_TIME_REACHED",
                now.minusHours(4),
                now.minusMinutes(1)
        );

        when(parkingSessionRepository.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));

        when(rules.evaluateExtension(eq(session),eq(30),any()))
                .thenReturn(quote(session,false,"SESSION_NOT_EXTENSIBLE",30));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.requestExtension(
                        session.getId(),
                        30,
                        "TEST_PROVIDER",
                        "TEST_METHOD",
                        "EXT-TEST-003"
                )
        );

        assertEquals(
                "SESSION_NOT_EXTENSIBLE",
                exception.getMessage()
        );


        verifyNoInteractions(paymentService);

        verify(sessionExtensionRepository, never())
                .save(any(SessionExtension.class));
    }

    @Test
    void shouldRejectExtensionThatExceedsMaximumContinuousTime() {
        OffsetDateTime now = OffsetDateTime.now();

        ParkingSession session = createSession(
                "ACTIVE",
                now.minusHours(3).minusMinutes(30),
                now.plusMinutes(30)
        );

        Tariff tariff = createTariff(240);

        when(parkingSessionRepository.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));

        when(sessionExtensionRepository
                .findPendingByParkingSessionId(session.getId()))
                .thenReturn(Optional.empty());

        when(rules.evaluateExtension(eq(session), any(), any())).thenReturn(quote(session, false, "MAX_CONTINUOUS_EXCEEDED", 60));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.requestExtension(
                        session.getId(),
                        60,
                        "TEST_PROVIDER",
                        "TEST_METHOD",
                        "EXT-TEST-004"
                )
        );

        assertEquals(
                "MAX_CONTINUOUS_EXCEEDED",
                exception.getMessage()
        );

        verifyNoInteractions(paymentService);

        verify(sessionExtensionRepository, never())
                .save(any(SessionExtension.class));
    }

    private ec.gob.simertpi.application.parking.rules.ParkingRulesResult quote(ParkingSession session, boolean allowed, String reason, int minutes) {
        return new ec.gob.simertpi.application.parking.rules.ParkingRulesResult(null, session.getParkingSpaceId(),
                java.time.Instant.now().atZone(java.time.ZoneOffset.UTC), allowed, allowed, false, null,
                "TEST", 30, 240, 10, "USD", BigDecimal.ONE, 60, new BigDecimal("0.50"), minutes, (long) minutes,
                session.getExpectedEndAt().plusMinutes(minutes).toInstant(), allowed, reason, session.getTariffId());
    }

    private ParkingSession createSession(
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime expectedEndAt
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

    private Tariff createTariff(Integer maxContinuousMinutes) {
        Tariff tariff = new Tariff();

        tariff.setId(UUID.randomUUID());
        tariff.setCode("TARIFA-TEST-EXTENSION");
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

    private Payment createPayment() {
        Payment payment = new Payment();

        payment.setId(UUID.randomUUID());
        payment.setParkingSessionId(UUID.randomUUID());
        payment.setProvider("TEST_PROVIDER");
        payment.setPaymentMethod("TEST_METHOD");
        payment.setAmount(BigDecimal.ZERO);
        payment.setCurrency("USD");
        payment.setStatus("PENDING");
        payment.setIdempotencyKey("EXT-TEST-001");
        payment.setCreatedAt(OffsetDateTime.now());
        payment.setUpdatedAt(OffsetDateTime.now());

        return payment;
    }
}
