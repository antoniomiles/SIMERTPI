package ec.gob.simertpi.domain.payments.entity;

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
@Table(name = "payments", schema = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "parking_session_id", nullable = false)
    private UUID parkingSessionId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_transaction_id", length = 150)
    private String providerTransactionId;

    @Column(name="provider_payment_id", length=150)
    private String providerPaymentId;
    @Column(name="provider_operation_key")
    private java.util.UUID providerOperationKey;
    @Column(name="provider_operation_status", length=20)
    private String providerOperationStatus;

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}