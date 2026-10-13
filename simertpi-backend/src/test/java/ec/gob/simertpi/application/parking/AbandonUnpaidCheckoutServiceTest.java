package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.ParkingConflictException;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbandonUnpaidCheckoutServiceTest {
    @Mock ParkingSessionRepository sessions;
    @Mock PaymentRepository payments;
    @Mock UserRepository users;

    private AbandonUnpaidCheckoutService service;
    private UUID sessionId;
    private UUID userId;
    private ParkingSession session;

    @BeforeEach
    void setUp() {
        service = new AbandonUnpaidCheckoutService(sessions, payments, users);
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        this.user = user;
        session = new ParkingSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setStatus("PENDING_PAYMENT");
        session.setStartedAt(OffsetDateTime.now().minusMinutes(2));
        session.setExpectedEndAt(OffsetDateTime.now().plusMinutes(28));
        session.setCreatedAt(OffsetDateTime.now().minusMinutes(2));
        session.setTotalAmount(BigDecimal.ZERO);
    }

    private User user;

    private void stubOwnerAndLockedSession() {
        when(users.findByUsername("citizen")).thenReturn(Optional.of(user));
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    }

    @Test
    void ownerCanImmediatelyCancelCheckoutWithoutAnyPayment() {
        stubOwnerAndLockedSession();
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.findByParkingSessionId(sessionId)).thenReturn(List.of());

        service.abandon(sessionId, "citizen");

        assertEquals("CANCELLED", session.getStatus());
        assertNotNull(session.getEndedAt());
        verify(sessions).save(session);
    }

    @Test
    void repeatedCancellationIsIdempotent() {
        stubOwnerAndLockedSession();
        session.setStatus("CANCELLED");
        session.setEndedAt(OffsetDateTime.now());

        assertDoesNotThrow(() -> service.abandon(sessionId, "citizen"));
        verifyNoInteractions(payments);
        verify(sessions, never()).save(any());
    }

    @Test
    void anotherOwnerCannotReleaseCheckout() {
        stubOwnerAndLockedSession();
        session.setUserId(UUID.randomUUID());

        assertThrows(ForbiddenException.class, () -> service.abandon(sessionId, "citizen"));
        verifyNoInteractions(payments);
        verify(sessions, never()).save(any());
    }

    @Test
    void inFlightOrUnknownPaymentPreventsRelease() {
        stubOwnerAndLockedSession();
        Payment pending = payment("PENDING", "REQUESTED");
        when(payments.findByParkingSessionId(sessionId)).thenReturn(List.of(pending));

        assertThrows(ParkingConflictException.class, () -> service.abandon(sessionId, "citizen"));
        assertEquals("PENDING_PAYMENT", session.getStatus());
        verify(sessions, never()).save(any());
    }

    @Test
    void approvedPaymentPreventsRelease() {
        stubOwnerAndLockedSession();
        Payment approved = payment("APPROVED", "CONFIRMED");
        when(payments.findByParkingSessionId(sessionId)).thenReturn(List.of(approved));

        assertThrows(ParkingConflictException.class, () -> service.abandon(sessionId, "citizen"));
        assertEquals("PENDING_PAYMENT", session.getStatus());
        verify(sessions, never()).save(any());
    }

    @Test
    void knownTerminalFailureCanBeReleased() {
        stubOwnerAndLockedSession();
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.findByParkingSessionId(sessionId))
                .thenReturn(List.of(payment("DECLINED", "CONFIRMED")));

        service.abandon(sessionId, "citizen");

        assertEquals("CANCELLED", session.getStatus());
        verify(sessions).save(session);
    }

    @Test
    void cleanupOnlyExpiresOldCheckoutWithoutPaymentRows() {
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        session.setCreatedAt(OffsetDateTime.now().minusMinutes(30));
        when(payments.findByParkingSessionId(sessionId)).thenReturn(List.of());

        assertTrue(service.expireUnstarted(sessionId, OffsetDateTime.now().minusMinutes(10)));
        assertEquals("CANCELLED", session.getStatus());
    }

    @Test
    void cleanupKeepsRecentOrPaymentBearingCheckout() {
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        session.setCreatedAt(OffsetDateTime.now());
        assertFalse(service.expireUnstarted(sessionId, OffsetDateTime.now().minusMinutes(10)));
        verifyNoInteractions(payments);
        verify(sessions, never()).save(any());
    }

    private Payment payment(String status, String operationStatus) {
        Payment payment = new Payment();
        payment.setStatus(status);
        payment.setProviderOperationStatus(operationStatus);
        return payment;
    }
}
