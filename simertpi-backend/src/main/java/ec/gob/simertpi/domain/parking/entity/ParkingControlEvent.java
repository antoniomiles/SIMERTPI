package ec.gob.simertpi.domain.parking.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "parking_control_events",
    schema = "parking",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_control_events_session_type",
            columnNames = {"parking_session_id", "event_type"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
public class ParkingControlEvent {

    @Id
    private UUID id;

    @Column(name = "parking_session_id", nullable = false)
    private UUID parkingSessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "parking_space_id", nullable = false)
    private UUID parkingSpaceId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "minutes_overdue")
    private Integer minutesOverdue;

    @Column(name = "source", nullable = false, length = 30)
    private String source = "SYSTEM";

    @Column(name = "status", nullable = false, length = 30)
    private String status = "RECORDED";

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
