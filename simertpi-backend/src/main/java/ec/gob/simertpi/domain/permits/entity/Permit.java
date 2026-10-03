package ec.gob.simertpi.domain.permits.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "permits", schema = "permits")
@Getter
@Setter
@NoArgsConstructor
public class Permit {
    @Id @Column(nullable = false) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "vehicle_id", nullable = false) private UUID vehicleId;
    @Column(name = "zone_id") private UUID zoneId;
    @Column(name = "permit_type", nullable = false, length = 50) private String permitType;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "valid_from", nullable = false) private OffsetDateTime validFrom;
    @Column(name = "valid_to", nullable = false) private OffsetDateTime validTo;
    @Column(name = "authorization_code", nullable = false, unique = true, length = 100)
    private String authorizationCode;
    @Column(length = 500) private String notes;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
}
