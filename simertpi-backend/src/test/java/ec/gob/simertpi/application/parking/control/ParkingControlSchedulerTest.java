package ec.gob.simertpi.application.parking.control;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParkingControlSchedulerTest {

    @Mock
    private ParkingSessionRepository parkingSessionRepository;

    @Mock
    private ParkingControlEvaluationService parkingControlEvaluationService;

    @InjectMocks
    private ParkingControlScheduler parkingControlScheduler;

    @Test
    void shouldEvaluateActiveExtendedAndExpiredSessions() {

        ParkingSession active = new ParkingSession();
        ParkingSession extended = new ParkingSession();
        ParkingSession expired = new ParkingSession();
        active.setId(UUID.randomUUID());
        extended.setId(UUID.randomUUID());
        expired.setId(UUID.randomUUID());

        List<String> expectedStatuses = List.of(
                "ACTIVE",
                "EXTENDED",
                "EXPIRED"
        );

        when(parkingSessionRepository.findByStatusIn(expectedStatuses))
                .thenReturn(List.of(active, extended, expired));

        parkingControlScheduler.evaluateParkingControls();

        verify(parkingControlEvaluationService).evaluate(active.getId());
        verify(parkingControlEvaluationService).evaluate(extended.getId());
        verify(parkingControlEvaluationService).evaluate(expired.getId());
    }

    @Test
    void continuesWhenAnIndividualEvaluationFails() {
        ParkingSession failed = new ParkingSession();
        ParkingSession next = new ParkingSession();
        failed.setId(UUID.randomUUID());
        next.setId(UUID.randomUUID());
        List<String> statuses = List.of("ACTIVE", "EXTENDED", "EXPIRED");
        when(parkingSessionRepository.findByStatusIn(statuses)).thenReturn(List.of(failed, next));
        org.mockito.Mockito.doThrow(new IllegalStateException("broken session"))
                .when(parkingControlEvaluationService).evaluate(failed.getId());

        parkingControlScheduler.evaluateParkingControls();

        verify(parkingControlEvaluationService).evaluate(failed.getId());
        verify(parkingControlEvaluationService).evaluate(next.getId());
    }

    @Test
    void shouldDoNothingWhenThereAreNoEvaluableSessions() {

        List<String> expectedStatuses = List.of(
                "ACTIVE",
                "EXTENDED",
                "EXPIRED"
        );

        when(parkingSessionRepository.findByStatusIn(expectedStatuses))
                .thenReturn(List.of());

        parkingControlScheduler.evaluateParkingControls();

        org.mockito.Mockito.verifyNoInteractions(
                parkingControlEvaluationService
        );
    }
}
