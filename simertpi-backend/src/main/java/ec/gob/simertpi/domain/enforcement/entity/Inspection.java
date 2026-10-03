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
@Table(name = "inspections", schema = "enforcement")
@Getter
@Setter
@NoArgsConstructor
public class Inspection {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "inspector_id", nullable = false)
    private UUID inspectorId;

    @Column(name = "parking_session_id")
    private UUID parkingSessionId;

    @Column(name = "parking_space_id", nullable = false)
    private UUID parkingSpaceId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "observed_plate", length = 10)
    private String observedPlate;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    @Column(name = "result", nullable = false, length = 30)
    private String result;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}