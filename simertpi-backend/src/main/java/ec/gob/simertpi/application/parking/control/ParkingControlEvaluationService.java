package ec.gob.simertpi.application.parking.control;

import ec.gob.simertpi.application.notifications.NotificationGenerationService;
import ec.gob.simertpi.application.notifications.NotificationEventIds;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.parking.entity.ParkingControlEvent;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingControlEventRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ParkingControlEvaluationService {
    private static final String ACTIVE = "ACTIVE";
    private static final String EXTENDED = "EXTENDED";
    private static final String EXPIRED = "EXPIRED";
    private static final String MAX_TIME_REACHED = "MAX_TIME_REACHED";
    private static final int MAX_TIME_WARNING_MINUTES = 30;

    private final ParkingControlEventRepository events;
    private final ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;
    private final ParkingSessionRepository sessions;
    private final NotificationGenerationService notificationGeneration;
    private final AuditService audit;

    public ParkingControlEvaluationService(ParkingControlEventRepository events,
                                           ec.gob.simertpi.application.parking.rules.ParkingRulesService rules,
                                           ParkingSessionRepository sessions,
                                           NotificationGenerationService notificationGeneration,
                                           AuditService audit) {
        this.events = events;
        this.rules = rules;
        this.sessions = sessions;
        this.notificationGeneration = notificationGeneration;
        this.audit = audit;
    }

    @Transactional
    public String evaluate(UUID sessionId) {
        return evaluate(sessionId, OffsetDateTime.now());
    }

    public String evaluate(ParkingSession session) {
        return evaluate(session, OffsetDateTime.now());
    }

    @Transactional
    public String evaluate(UUID sessionId, OffsetDateTime now) {
        ParkingSession session = sessions.findById(sessionId).orElse(null);
        if (session != null) {
            return evaluate(session, now);
        }
        return "SESSION_NOT_FOUND";
    }

    /** Deterministic entry point used by focused lifecycle tests. */
    @Transactional
    public String evaluate(ParkingSession session, OffsetDateTime now) {
        if (session == null || now == null || session.getExpectedEndAt() == null) return "IGNORED";
        String status = session.getStatus();
        if (!ACTIVE.equals(status) && !EXTENDED.equals(status)
                && !EXPIRED.equals(status) && !MAX_TIME_REACHED.equals(status)) return "IGNORED";

        var policy = rules.sessionPolicy(session);
        evaluateMaximumContinuousTime(session, now, policy.maximumContinuousMinutes());
        if (MAX_TIME_REACHED.equals(session.getStatus())) return "MAX_TIME_REACHED";
        if (now.isBefore(session.getExpectedEndAt())) return policy.reasonCode();

        Duration overdue = Duration.between(session.getExpectedEndAt(), now);
        long wholeMinutes = Math.max(0, overdue.toMinutes());
        long overdueMinutes = overdue.minusMinutes(wholeMinutes).isZero()
                ? wholeMinutes : wholeMinutes + 1;
        record(session, "EXPIRATION", 0, now);
        if (ACTIVE.equals(session.getStatus()) || EXTENDED.equals(session.getStatus())) {
            session.setStatus(EXPIRED);
            session.setUpdatedAt(now);
        }

        // Missing policy never implies zero grace or permission to sanction.
        if (!"RULES_RESOLVED".equals(policy.reasonCode())) return policy.reasonCode();
        if (overdue.compareTo(Duration.ofMinutes(policy.gracePeriodMinutes())) <= 0) {
            record(session, "GRACE_PERIOD", (int) overdueMinutes, now);
            return "GRACE_PERIOD";
        }
        record(session, "AMONESTACION", (int) overdueMinutes, now);
        if (overdueMinutes <= 30) record(session, "EXCESS_11_30", (int) overdueMinutes, now);
        else if (overdueMinutes <= 60) record(session, "EXCESS_31_60", (int) overdueMinutes, now);
        else if (overdueMinutes <= 120) record(session, "EXCESS_61_120", (int) overdueMinutes, now);
        else record(session, "EXCESS_OVER_120", (int) overdueMinutes, now);
        return "EXCESS_RECORDED";
    }

    private void evaluateMaximumContinuousTime(ParkingSession session, OffsetDateTime now, Integer maximumMinutes) {
        if (session.getStartedAt() == null || maximumMinutes == null) return;
        long elapsed = Duration.between(session.getStartedAt(), now).toMinutes();
        long remaining = maximumMinutes - elapsed;
        if (remaining > MAX_TIME_WARNING_MINUTES) return;
        if (remaining > 0) {
            record(session, "MAX_TIME_WARNING", 0, now);
            return;
        }
        record(session, "MAX_TIME_REACHED", 0, now);
        if (!MAX_TIME_REACHED.equals(session.getStatus())) {
            session.setStatus(MAX_TIME_REACHED);
            session.setUpdatedAt(now);
        }
    }

    private void record(ParkingSession session, String type, int overdue, OffsetDateTime at) {
        UUID eventId = UUID.randomUUID();
        int inserted = events.insertIfAbsent(eventId, session.getId(), session.getUserId(),
                session.getVehicleId(), session.getParkingSpaceId(), type, at, overdue);
        if (inserted == 0) return;
        audit.recordOutcome("PARKING_CONTROL_" + type, "PARKING_SESSION", session.getId(), "SUCCESS",
                Map.of("eventId", eventId, "minutesOverdue", overdue));

        ParkingControlEvent event = new ParkingControlEvent();
        event.setId(eventId);
        event.setParkingSessionId(session.getId());
        event.setUserId(session.getUserId());
        event.setVehicleId(session.getVehicleId());
        event.setParkingSpaceId(session.getParkingSpaceId());
        event.setEventType(type);
        event.setOccurredAt(at);
        event.setMinutesOverdue(overdue);

        UUID sourceEventId = "EXPIRATION".equals(type)
                ? NotificationEventIds.stable("PARKING_SESSION", session.getId(), type, session.getExpectedEndAt())
                : eventId;
        notificationGeneration.generate(session.getUserId(), type, sourceEventId, null,
                "PARKING_CONTROL_EVENT", eventId, at,
                Map.of("parkingSessionId", session.getId(), "status", session.getStatus(),
                        "minutesOverdue", overdue));
    }
}
