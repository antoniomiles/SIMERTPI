package ec.gob.simertpi.application.parking.control;

import ec.gob.simertpi.application.notifications.NotificationGenerationService;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.repository.ParkingControlEventRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParkingControlEvaluationServiceTest {
    @Mock ParkingControlEventRepository events;
    @Mock ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;
    @Mock ParkingSessionRepository sessions;
    @Mock NotificationGenerationService notificationGeneration;
    @Mock AuditService audit;
    @InjectMocks ParkingControlEvaluationService service;

    @Test
    void expiresAtContractEndWithoutRecordingSanctionDuringGrace() {
        OffsetDateTime end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        ParkingSession session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(240, 10, "RULES_RESOLVED"));
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt())).thenReturn(1);

        service.evaluate(session, end.plusMinutes(5));

        assertEquals("EXPIRED", session.getStatus());
        assertEquals(null, session.getEndedAt());
        verify(events, times(2)).insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt());
        verify(events, never()).insertIfAbsent(any(), any(), any(), any(), any(), eq("EXCESS_11_30"), any(), anyInt());
    }

    @Test
    void recordsExpirationAndExcessOnceAcrossRepeatedEvaluation() {
        OffsetDateTime end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        ParkingSession session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(240, 10, "RULES_RESOLVED"));
        AtomicInteger insertionAttempts = new AtomicInteger();
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt()))
                .thenAnswer(invocation -> insertionAttempts.getAndIncrement() == 0 ? 1 : 0);

        service.evaluate(session, end.plusMinutes(20));
        service.evaluate(session, end.plusMinutes(21));

        assertEquals("EXPIRED", session.getStatus());
        verify(events, times(6)).insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt());
    }

    @Test
    void usesEveryExcessBandAndGraceBoundary() {
        assertOverdueBand(10, "GRACE_PERIOD");
        assertOverdueBand(11, "EXCESS_11_30");
        assertOverdueBand(30, "EXCESS_11_30");
        assertOverdueBand(31, "EXCESS_31_60");
        assertOverdueBand(60, "EXCESS_31_60");
        assertOverdueBand(61, "EXCESS_61_120");
        assertOverdueBand(120, "EXCESS_61_120");
        assertOverdueBand(121, "EXCESS_OVER_120");
    }

    @Test
    void appliesGraceByExactElapsedTimeAndRoundsSanctionBandUp() {
        OffsetDateTime end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        OffsetDateTime evaluation = end.plusMinutes(10).plusSeconds(1);
        ParkingSession session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(1000, 10, "RULES_RESOLVED"));
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt())).thenReturn(1);

        service.evaluate(session, evaluation);

        verify(events).insertIfAbsent(any(), eq(session.getId()), any(), any(), any(),
                eq("EXCESS_11_30"), eq(evaluation), eq(11));
    }

    @Test
    void marksMaximumTimeOnceAndDoesNotAlsoMarkExpiry() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        ParkingSession session = session("ACTIVE", now.minusMinutes(240), now.minusMinutes(20));
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(240, 10, "RULES_RESOLVED"));
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt())).thenReturn(1);

        service.evaluate(session, now);

        assertEquals("MAX_TIME_REACHED", session.getStatus());
        verify(events).insertIfAbsent(any(), eq(session.getId()), any(), any(), any(), eq("MAX_TIME_REACHED"), eq(now), eq(0));
        verify(events, never()).insertIfAbsent(any(), any(), any(), any(), any(), eq("EXPIRATION"), any(), anyInt());
    }

    @Test
    void emitsNotificationsOnlyWhenAControlEventWasInserted() {
        OffsetDateTime end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        ParkingSession session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(240, 10, "RULES_RESOLVED"));
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt())).thenReturn(0);

        service.evaluate(session, end.plusMinutes(5));

        verifyNoInteractions(notificationGeneration);
    }

    @Test
    void missingGraceExpiresPurchasedTimeWithoutSanction() {
        var end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        var session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(300, null, "NO_CONTROL_CONFIGURATION"));
        assertEquals("NO_CONTROL_CONFIGURATION", service.evaluate(session, end.plusMinutes(50)));
        assertEquals("EXPIRED", session.getStatus());
        verify(events, never()).insertIfAbsent(any(), any(), any(), any(), any(), eq("AMONESTACION"), any(), anyInt());
    }

    @Test
    void usesConfiguredGraceInsteadOfTenMinutes() {
        var end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        var session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(300, 25, "RULES_RESOLVED"));
        assertEquals("GRACE_PERIOD", service.evaluate(session, end.plusMinutes(20)));
        verify(events, never()).insertIfAbsent(any(), any(), any(), any(), any(), eq("AMONESTACION"), any(), anyInt());
    }

    private void assertOverdueBand(int minutes, String expectedEvent) {
        reset(events, rules, notificationGeneration);
        OffsetDateTime end = OffsetDateTime.parse("2026-10-02T10:00:00Z");
        ParkingSession session = session("ACTIVE", end.minusHours(1), end);
        when(rules.sessionPolicy(session)).thenReturn(new ec.gob.simertpi.application.parking.rules.ParkingRulesService.SessionPolicy(1000, 10, "RULES_RESOLVED"));
        when(events.insertIfAbsent(any(), any(), any(), any(), any(), anyString(), any(), anyInt())).thenReturn(1);
        service.evaluate(session, end.plusMinutes(minutes));
        verify(events).insertIfAbsent(any(), eq(session.getId()), any(), any(), any(), eq(expectedEvent), any(), anyInt());
    }

    private ParkingSession session(String status, OffsetDateTime start, OffsetDateTime end) {
        ParkingSession session = new ParkingSession();
        session.setId(UUID.randomUUID()); session.setUserId(UUID.randomUUID());
        session.setVehicleId(UUID.randomUUID()); session.setParkingSpaceId(UUID.randomUUID());
        session.setTariffId(UUID.randomUUID()); session.setStartedAt(start);
        session.setExpectedEndAt(end); session.setStatus(status);
        return session;
    }

    private Tariff tariff(int maxMinutes) {
        Tariff tariff = new Tariff(); tariff.setId(UUID.randomUUID());
        tariff.setCode("CONTROL"); tariff.setName("Control");
        tariff.setAmount(BigDecimal.ONE); tariff.setDurationMinutes(60);
        tariff.setMinMinutes(1); tariff.setMaxContinuousMinutes(maxMinutes);
        tariff.setActive(true); tariff.setValidFrom(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return tariff;
    }
}
