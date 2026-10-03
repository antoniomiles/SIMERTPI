package ec.gob.simertpi.application.permits;

import ec.gob.simertpi.domain.permits.entity.Permit;

import java.util.UUID;

public interface PermitEventPublisher {
    void publish(Permit permit, String eventType, UUID actorUserId);
}
