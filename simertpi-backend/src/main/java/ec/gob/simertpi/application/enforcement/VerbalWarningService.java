package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.*;
import ec.gob.simertpi.application.audit.Audited;
import ec.gob.simertpi.application.parking.rules.ParkingRulesService;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class VerbalWarningService {
    @org.springframework.beans.factory.annotation.Autowired
    private ec.gob.simertpi.application.notifications.NotificationGenerationService notifications;
    private final InspectorAuthorizationService authorization;
    private final ParkingSessionRepository sessions;
    private final ParkingRulesService rules;
    private final VerbalWarningStore store;
    public VerbalWarningService(InspectorAuthorizationService authorization, ParkingSessionRepository sessions,
                               ParkingRulesService rules, VerbalWarningStore store) {
        this.authorization=authorization; this.sessions=sessions; this.rules=rules; this.store=store;
    }
    @Transactional
    @Audited(action="VERBAL_WARNING_REGISTERED", resourceType="PARKING_SESSION", resourceIdArgument=1, idempotencyArgument=3)
    public VerbalWarningStore.Warning register(String username, UUID sessionId, String observation, String key) {
        var inspector=authorization.requireInspector(username);
        if (observation==null || observation.isBlank() || observation.length()>VerbalWarningStore.MAX_OBSERVATION)
            throw new InvalidRequestException("Observation must contain 1 to 500 plain-text characters");
        if (observation.contains("<") || observation.contains(">") || observation.indexOf('\u0000')>=0)
            throw new InvalidRequestException("Observation must be plain text");
        if (key==null || key.isBlank() || key.length()>128) throw new InvalidRequestException("Invalid idempotency key");
        var session=sessions.findByIdForUpdate(sessionId).orElseThrow(()->new ResourceNotFoundException("Parking session not found"));
        // Municipal credentials must not be used to unlock the inspector's own citizen session.
        if (inspector.getId().equals(session.getUserId())) throw new ForbiddenException();
        String text=observation.strip();
        var replay=store.byKey(inspector.getId(),key);
        if (replay.isPresent()) {
            if (!replay.get().parkingSessionId().equals(sessionId) || !replay.get().observation().equals(text))
                throw new IdempotencyConflictException();
            return replay.get();
        }
        var state=rules.lifecycle(session,Instant.now(),0).state();
        if (!java.util.Set.of("GRACE_EXCEEDED","REGULARIZATION_ALLOWED","MAX_TIME_REACHED").contains(state))
            throw new ParkingConflictException("VERBAL_WARNING_NOT_ALLOWED");
        var policy=rules.sessionPolicy(session);
        if (policy.gracePeriodMinutes()==null || session.getExpectedEndAt()==null
                || Instant.now().isBefore(session.getExpectedEndAt().toInstant().plusSeconds(policy.gracePeriodMinutes()*60L)))
            throw new ParkingConflictException("VERBAL_WARNING_NOT_ALLOWED");
        var existing=store.forExpiry(sessionId,session.getExpectedEndAt());
        if (existing.isPresent()) throw new ParkingConflictException("VERBAL_WARNING_ALREADY_RECORDED");
        var warning=store.insert(sessionId,inspector.getId(),session.getExpectedEndAt(),text,key);
        notifications.generate(session.getUserId(),"VERBAL_WARNING",warning.id(),null,
                "PARKING_SESSION",sessionId,warning.recordedAt(),java.util.Map.of());
        return warning;
    }
}
