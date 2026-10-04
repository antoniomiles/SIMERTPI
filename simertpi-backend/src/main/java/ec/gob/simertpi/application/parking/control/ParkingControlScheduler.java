package ec.gob.simertpi.application.parking.control;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "simertpi.control.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ParkingControlScheduler {

    private static final Logger log = LoggerFactory.getLogger(ParkingControlScheduler.class);

    private static final List<String> EVALUABLE_STATUSES = List.of(
            "ACTIVE",
            "EXTENDED",
            "EXPIRED"
    );

    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingControlEvaluationService parkingControlEvaluationService;

    public ParkingControlScheduler(
            ParkingSessionRepository parkingSessionRepository,
            ParkingControlEvaluationService parkingControlEvaluationService
    ) {
        this.parkingSessionRepository = parkingSessionRepository;
        this.parkingControlEvaluationService = parkingControlEvaluationService;
    }

    @Scheduled(
            fixedDelayString = "${simertpi.control.scheduler.fixed-delay-ms:60000}"
    )
    public void evaluateParkingControls() {

        List<ParkingSession> sessions =
                parkingSessionRepository.findByStatusIn(
                        EVALUABLE_STATUSES
                );

        for (ParkingSession session : sessions) {
            try {
                parkingControlEvaluationService.evaluate(session.getId());
            } catch (RuntimeException exception) {
                ec.gob.simertpi.application.operations.OperationalMetrics.itemFailure();
                log.error("event=parking_control_item result=FAILED scheduler=PARKING_CONTROL");
            }
        }
    }
}
