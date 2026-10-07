package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.domain.configuration.entity.NotificationRule;
import ec.gob.simertpi.domain.configuration.repository.NotificationRuleRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.permits.entity.Permit;
import ec.gob.simertpi.infrastructure.permits.JpaPermitRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
public class NotificationReminderScheduler {
    @org.springframework.beans.factory.annotation.Autowired
    private ec.gob.simertpi.application.parking.ParkingAvailabilityService availability;
    private final NotificationRuleRepository rules;
    private final ParkingSessionRepository sessions;
    private final JpaPermitRepository permits;
    private final NotificationGenerationService generation;

    public NotificationReminderScheduler(NotificationRuleRepository rules, ParkingSessionRepository sessions,
                                         JpaPermitRepository permits, NotificationGenerationService generation) {
        this.rules = rules;
        this.sessions = sessions;
        this.permits = permits;
        this.generation = generation;
    }

    @Scheduled(fixedDelayString = "${simertpi.notifications.reminders.fixed-delay-ms:60000}")
    @Transactional
    public void generateDueReminders() {
        OffsetDateTime now = OffsetDateTime.now();
        scheduleParkingExpirations(now);
        schedulePermitExpirations(now);
    }

    private void scheduleParkingExpirations(OffsetDateTime now) {
        List<NotificationRule> activeRules = rules.findActiveForEventAt("PARKING_ENDING_SOON", now);
        if (activeRules.isEmpty()) return;
        for (ParkingSession session : sessions.findByStatusIn(List.of("ACTIVE", "EXTENDED"))) {
            if (session.getExpectedEndAt() == null || !session.getExpectedEndAt().isAfter(now)) continue;
            for (NotificationRule rule : activeRules) {
                OffsetDateTime dueAt = session.getExpectedEndAt().minusSeconds(availability.endingSoonSeconds());
                if (!now.isBefore(dueAt)) {
                    var eventId = NotificationEventIds.stable("PARKING_SESSION", session.getId(),
                            "EXPIRATION", session.getExpectedEndAt());
                    generation.createForRule(session.getUserId(), rule, "PARKING_ENDING_SOON", eventId, null,
                            "PARKING_SESSION", session.getId(), now,
                            Map.of("parkingSessionId", session.getId(), "expectedEndAt", session.getExpectedEndAt(),
                                    "minutesBefore", rule.getMinutesBefore()));
                }
            }
        }
    }

    private void schedulePermitExpirations(OffsetDateTime now) {
        List<NotificationRule> activeRules = reminderRules("PERMIT_EXPIRED", now);
        if (activeRules.isEmpty()) return;
        int largestLead = activeRules.stream().mapToInt(NotificationRule::getMinutesBefore).max().orElse(0);
        OffsetDateTime horizon = now.plusMinutes(largestLead);
        for (Permit permit : permits.findByStatusAndValidToBetween("ACTIVE", now, horizon)) {
            for (NotificationRule rule : activeRules) {
                OffsetDateTime dueAt = permit.getValidTo().minusMinutes(rule.getMinutesBefore());
                if (rule.getMinutesBefore() > 0 && !now.isBefore(dueAt)) {
                    var eventId = NotificationEventIds.stable("PERMIT", permit.getId(),
                            "PERMIT_EXPIRED", permit.getValidTo());
                    generation.createForRule(permit.getUserId(), rule, "PERMIT_EXPIRED", eventId, null,
                            "PERMIT", permit.getId(), now,
                            Map.of("permitId", permit.getId(), "validTo", permit.getValidTo(),
                                    "permitType", permit.getPermitType(), "minutesBefore", rule.getMinutesBefore()));
                }
            }
        }
    }

    private List<NotificationRule> reminderRules(String eventType, OffsetDateTime now) {
        return rules.findActiveForEventAt(eventType, now).stream()
                .filter(rule -> rule.getMinutesBefore() != null && rule.getMinutesBefore() > 0)
                .toList();
    }
}
