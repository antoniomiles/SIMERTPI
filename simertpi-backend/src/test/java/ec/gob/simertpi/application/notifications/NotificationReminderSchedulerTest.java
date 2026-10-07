package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.application.parking.ParkingAvailabilityService;
import ec.gob.simertpi.domain.configuration.entity.NotificationRule;
import ec.gob.simertpi.domain.configuration.repository.NotificationRuleRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.infrastructure.permits.JpaPermitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class NotificationReminderSchedulerTest {
    @Test void zeroLeadInAppRuleUsesSameThresholdAsHomeAndNewExpirationGetsNewEvent() {
        var rules=mock(NotificationRuleRepository.class);var sessions=mock(ParkingSessionRepository.class);
        var permits=mock(JpaPermitRepository.class);var generation=mock(NotificationGenerationService.class);
        var availability=mock(ParkingAvailabilityService.class);
        var scheduler=new NotificationReminderScheduler(rules,sessions,permits,generation);
        ReflectionTestUtils.setField(scheduler,"availability",availability);
        when(availability.endingSoonSeconds()).thenReturn(600L);
        var rule=new NotificationRule();rule.setId(UUID.randomUUID());rule.setMinutesBefore(0);
        when(rules.findActiveForEventAt(eq("PARKING_ENDING_SOON"),any())).thenReturn(List.of(rule));
        var session=new ParkingSession();session.setId(UUID.randomUUID());session.setUserId(UUID.randomUUID());
        session.setExpectedEndAt(OffsetDateTime.now().plusMinutes(9));
        when(sessions.findByStatusIn(any())).thenReturn(List.of(session));
        scheduler.generateDueReminders();
        var sources=org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(generation).createForRule(eq(session.getUserId()),eq(rule),eq("PARKING_ENDING_SOON"),sources.capture(),isNull(),eq("PARKING_SESSION"),eq(session.getId()),any(),anyMap());
        UUID previous=sources.getValue();
        assertThat(previous).isEqualTo(NotificationEventIds.stable("PARKING_SESSION",session.getId(),"EXPIRATION",session.getExpectedEndAt()));
        clearInvocations(generation);
        session.setExpectedEndAt(OffsetDateTime.now().plusMinutes(30));scheduler.generateDueReminders();verifyNoInteractions(generation);
        session.setExpectedEndAt(OffsetDateTime.now().plusMinutes(8));scheduler.generateDueReminders();
        verify(generation).createForRule(any(),any(),eq("PARKING_ENDING_SOON"),sources.capture(),isNull(),anyString(),any(),any(),anyMap());
        assertThat(sources.getValue()).isNotEqualTo(previous);
    }
    @Test void expiredSessionsDoNotGetAdvanceReminders() {
        var rules=mock(NotificationRuleRepository.class);var sessions=mock(ParkingSessionRepository.class);var generation=mock(NotificationGenerationService.class);
        var scheduler=new NotificationReminderScheduler(rules,sessions,mock(JpaPermitRepository.class),generation);
        var rule=new NotificationRule();rule.setMinutesBefore(10);
        when(rules.findActiveForEventAt(eq("PARKING_ENDING_SOON"),any())).thenReturn(List.of(rule));
        var session=new ParkingSession();session.setExpectedEndAt(OffsetDateTime.now().minusSeconds(1));
        when(sessions.findByStatusIn(any())).thenReturn(List.of(session));
        scheduler.generateDueReminders();verifyNoInteractions(generation);
    }
}
