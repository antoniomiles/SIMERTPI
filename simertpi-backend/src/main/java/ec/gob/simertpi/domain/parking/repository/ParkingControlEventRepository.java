package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.ParkingControlEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ParkingControlEventRepository
        extends JpaRepository<ParkingControlEvent, UUID> {

    Optional<ParkingControlEvent> findByParkingSessionIdAndEventType(
            UUID parkingSessionId,
            String eventType
    );

    List<ParkingControlEvent> findByParkingSessionIdOrderByOccurredAtAsc(UUID parkingSessionId);

    @Modifying
    @Query(value = """
            INSERT INTO parking.parking_control_events
              (id, parking_session_id, user_id, vehicle_id, parking_space_id,
               event_type, occurred_at, minutes_overdue, source, status, created_at, updated_at)
            VALUES (:id, :sessionId, :userId, :vehicleId, :spaceId,
                    :eventType, :occurredAt, :minutesOverdue, 'SYSTEM', 'RECORDED', :occurredAt, :occurredAt)
            ON CONFLICT (parking_session_id, event_type) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("sessionId") UUID sessionId,
                       @Param("userId") UUID userId,
                       @Param("vehicleId") UUID vehicleId,
                       @Param("spaceId") UUID spaceId,
                       @Param("eventType") String eventType,
                       @Param("occurredAt") java.time.OffsetDateTime occurredAt,
                       @Param("minutesOverdue") Integer minutesOverdue);
}
