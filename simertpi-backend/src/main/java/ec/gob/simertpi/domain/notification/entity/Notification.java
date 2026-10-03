package ec.gob.simertpi.domain.notification.entity;

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
@Table(name = "notifications", schema = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;
    @Column(nullable = false, length = 30)
    private String channel;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(name = "reference_type", length = 50)
    private String referenceType;
    @Column(name = "reference_id")
    private UUID referenceId;
    @Column(nullable = false, length = 30)
    private String status = "PENDING";
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;
    @Column(name = "read_at")
    private OffsetDateTime readAt;
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    @Column(name = "source_event_id")
    private UUID sourceEventId;
    @Column(name = "outbox_event_id")
    private UUID outboxEventId;
    @Column(name = "rule_id")
    private UUID ruleId;
    @Column(length = 255)
    private String recipient;
    @Column(name = "provider_reference", length = 255)
    private String providerReference;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
