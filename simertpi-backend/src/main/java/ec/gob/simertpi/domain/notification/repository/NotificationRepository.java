package ec.gob.simertpi.domain.notification.repository;

import ec.gob.simertpi.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);
    Optional<Notification> findBySourceEventIdAndUserIdAndChannelAndRuleId(
            UUID sourceEventId, UUID userId, String channel, UUID ruleId);

    @Modifying
    @Query(value = """
            INSERT INTO notification.notifications
                (id, user_id, notification_type, channel, title, message, reference_type,
                 reference_id, status, source_event_id, outbox_event_id, rule_id, recipient,
                 attempt_count, created_at, updated_at)
            VALUES (:id, :userId, :type, :channel, :title, :message, :referenceType,
                    :referenceId, 'PENDING', :sourceEventId, :outboxEventId, :ruleId,
                    :recipient, 0, :now, :now)
            ON CONFLICT (source_event_id, user_id, channel, rule_id)
                WHERE source_event_id IS NOT NULL AND rule_id IS NOT NULL
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
                       @Param("type") String type, @Param("channel") String channel,
                       @Param("title") String title, @Param("message") String message,
                       @Param("referenceType") String referenceType, @Param("referenceId") UUID referenceId,
                       @Param("sourceEventId") UUID sourceEventId, @Param("outboxEventId") UUID outboxEventId,
                       @Param("ruleId") UUID ruleId, @Param("recipient") String recipient,
                       @Param("now") OffsetDateTime now);

    @Query(value = "select * from notification.notifications where status='FAILED' " +
            "and next_attempt_at <= :now order by next_attempt_at limit 100 for update skip locked",
            nativeQuery = true)
    List<Notification> lockDueRetries(@Param("now") OffsetDateTime now);
}
