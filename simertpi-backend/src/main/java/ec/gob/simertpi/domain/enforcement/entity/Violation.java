package ec.gob.simertpi.domain.enforcement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "violations", schema = "enforcement")
@Getter
@Setter
@NoArgsConstructor
public class Violation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "inspection_id", nullable = false)
    private UUID inspectionId;

    @Column(name = "inspector_id", nullable = false)
    private UUID inspectorId;

    @Column(name = "parking_session_id")
    private UUID parkingSessionId;

    @Column(name = "parking_space_id", nullable = false)
    private UUID parkingSpaceId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "plate", length = 10)
    private String plate;

    @Column(name = "violation_type", nullable = false, length = 50)
    private String violationType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "fine_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal fineAmount;

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
