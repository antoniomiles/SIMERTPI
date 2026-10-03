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
@Table(name = "tariffs", schema = "parking")
@Getter
@Setter
@NoArgsConstructor
public class Tariff {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "rounding_mode", length = 20)
    private String roundingMode;

    @Column(name = "grace_period_minutes")
    private Integer gracePeriodMinutes;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "min_minutes", nullable = false)
    private Integer minMinutes;

    @Column(name = "max_continuous_minutes")
    private Integer maxContinuousMinutes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}