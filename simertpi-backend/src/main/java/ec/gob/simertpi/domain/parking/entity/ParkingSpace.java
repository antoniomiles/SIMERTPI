package ec.gob.simertpi.domain.parking.entity;

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
@Table(name = "parking_spaces", schema = "parking")
@Getter
@Setter
@NoArgsConstructor
public class ParkingSpace {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "street_id", nullable = false)
    private UUID streetId;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "qr_code", nullable = false, unique = true, length = 255)
    private String qrCode;

    @Column(name = "space_number", nullable = false, length = 20)
    private String spaceNumber;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "space_type", nullable = false, length = 30)
    private String spaceType = "STANDARD";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
