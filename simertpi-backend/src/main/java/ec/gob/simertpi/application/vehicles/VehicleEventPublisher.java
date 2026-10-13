package ec.gob.simertpi.application.vehicles;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Transactional local outbox only: never invokes Firebase from a vehicle operation. */
@Component
public class VehicleEventPublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public VehicleEventPublisher(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(Vehicle vehicle,String event) {
        if(!java.util.Set.of("VEHICLE_REGISTERED","VEHICLE_DELETED").contains(event)) throw new IllegalArgumentException("Unsupported vehicle event");
        UUID id=UUID.nameUUIDFromBytes(("VEHICLE:"+vehicle.getId()+":"+event).getBytes(StandardCharsets.UTF_8));
        try {
            String payload=mapper.writeValueAsString(Map.of("ownerId",vehicle.getUserId(),"plate",vehicle.getPlate()));
            OffsetDateTime now=OffsetDateTime.now();
            jdbc.update("""
                INSERT INTO audit.outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at,created_at,updated_at)
                VALUES (?,'VEHICLE',?,?,?,'PENDING',?,?,?) ON CONFLICT(id) DO NOTHING
                """,id,vehicle.getId(),event,payload,now,now,now);
        } catch(JsonProcessingException failure) { throw new IllegalStateException("Vehicle event serialization failed",failure); }
    }
}
