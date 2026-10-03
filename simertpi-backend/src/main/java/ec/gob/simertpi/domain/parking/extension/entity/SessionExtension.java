package ec.gob.simertpi.domain.parking.extension.entity;

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
@Table(name = "session_extensions", schema = "parking")
@Getter
@Setter
@NoArgsConstructor
public class SessionExtension {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "parking_session_id", nullable = false)
    private UUID parkingSessionId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "additional_minutes", nullable = false)
    private Integer additionalMinutes;

    @Column(name = "previous_expected_end_at", nullable = false)
    private OffsetDateTime previousExpectedEndAt;

    @Column(name = "new_expected_end_at", nullable = false)
    private OffsetDateTime newExpectedEndAt;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
