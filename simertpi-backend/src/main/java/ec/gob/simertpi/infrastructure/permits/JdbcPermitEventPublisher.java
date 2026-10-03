package ec.gob.simertpi.infrastructure.permits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.permits.PermitEventPublisher;
import ec.gob.simertpi.domain.permits.entity.Permit;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcPermitEventPublisher implements PermitEventPublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPermitEventPublisher(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void publish(Permit permit, String eventType, UUID actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> event = new HashMap<>();
        event.put("permitId", permit.getId());
        event.put("beneficiaryUserId", permit.getUserId());
        event.put("vehicleId", permit.getVehicleId());
        event.put("zoneId", permit.getZoneId());
        event.put("permitType", permit.getPermitType());
        event.put("status", permit.getStatus());
        event.put("validFrom", permit.getValidFrom());
        event.put("validTo", permit.getValidTo());
        event.put("actorUserId", actorUserId);
        event.put("correlationId", MDC.get("correlationId"));
        try {
            jdbc.update("""
                    INSERT INTO audit.outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload, status, occurred_at, created_at, updated_at)
                    VALUES (?, 'PERMIT', ?, ?, ?, 'PENDING', ?, ?, ?)
                    """, UUID.randomUUID(), permit.getId(), eventType, mapper.writeValueAsString(event), now, now, now);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("No se pudo serializar el evento del permiso", serializationFailure);
        }
    }
}
