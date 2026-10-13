package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.ParkingConflictException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.audit.Audited;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Releases only unpaid checkout sessions whose provider state is known to be terminal. */
@Service
public class AbandonUnpaidCheckoutService {
    private final ParkingSessionRepository sessions;
    private final PaymentRepository payments;
    private final UserRepository users;

    public AbandonUnpaidCheckoutService(ParkingSessionRepository sessions,
                                        PaymentRepository payments,
                                        UserRepository users) {
        this.sessions = sessions;
        this.payments = payments;
        this.users = users;
    }

    @Transactional
    @Audited(action = "PARKING_CHECKOUT_ABANDONED", resourceType = "PARKING_SESSION", resourceIdArgument = 0)
    public void abandon(UUID sessionId, String username) {
        var user = users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        ParkingSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
        if (!user.getId().equals(session.getUserId())) throw new ForbiddenException();
        cancelIfSafe(session);
    }

    @Transactional
    public boolean expireUnstarted(UUID sessionId, OffsetDateTime cutoff) {
        ParkingSession session = sessions.findByIdForUpdate(sessionId).orElse(null);
        if (session == null || !"PENDING_PAYMENT".equals(session.getStatus())
                || session.getCreatedAt() == null || session.getCreatedAt().isAfter(cutoff)) return false;
        if (!payments.findByParkingSessionId(sessionId).isEmpty()) return false;
        cancel(session);
        return true;
    }

    private void cancelIfSafe(ParkingSession session) {
        if ("CANCELLED".equals(session.getStatus())) return;
        if (!"PENDING_PAYMENT".equals(session.getStatus()) || session.getEndedAt() != null) {
            throw new ParkingConflictException("CHECKOUT_NOT_ABANDONABLE");
        }
        var attempts = payments.findByParkingSessionId(session.getId());
        if (attempts.stream().anyMatch(payment ->
                !java.util.Set.of("FAILED", "DECLINED", "CANCELLED").contains(payment.getStatus())
                        || !"CONFIRMED".equals(payment.getProviderOperationStatus()))) {
            throw new ParkingConflictException("PAYMENT_OPERATION_MAY_HAVE_STARTED");
        }
        cancel(session);
    }

    private void cancel(ParkingSession session) {
        OffsetDateTime now = OffsetDateTime.now();
        session.setStatus("CANCELLED");
        session.setEndedAt(now);
        session.setUpdatedAt(now);
        sessions.save(session);
    }
}
