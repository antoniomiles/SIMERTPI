package ec.gob.simertpi.domain.configuration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "notification_rules",
    schema = "configuration",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification_rules_code_channel",
            columnNames = {"code", "channel"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
public class NotificationRule {

    @Column(nullable = false, length = 20)
    private String classification = "INFORMATIONAL";
    @Column(nullable = false)
    private boolean mandatory;

    @Id
    private UUID id;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "minutes_before", nullable = false)
    private Integer minutesBefore = 0;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "title_template", nullable = false, length = 255)
    private String titleTemplate;

    @Column(name = "message_template", nullable = false, length = 1000)
    private String messageTemplate;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
