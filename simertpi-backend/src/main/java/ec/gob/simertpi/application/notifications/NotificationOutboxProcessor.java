package ec.gob.simertpi.application.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class NotificationOutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxProcessor.class);
    private static final Set<String> EVENT_TYPES = Set.of("PERMIT_CREATED", "PERMIT_CANCELLED", "PERMIT_EXPIRED",
            "PAYMENT_CREATED", "PAYMENT_APPROVED", "PAYMENT_DECLINED", "PAYMENT_FAILED", "PAYMENT_CANCELLED_TIMEOUT");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NotificationGenerationService generation;
    private final PaymentRepository payments;
    private final ParkingSessionRepository sessions;

    public NotificationOutboxProcessor(JdbcTemplate jdbc, ObjectMapper mapper,
                                        NotificationGenerationService generation,
                                        PaymentRepository payments, ParkingSessionRepository sessions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.generation = generation;
        this.payments = payments;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${simertpi.notifications.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void processPending() {
        OffsetDateTime now = OffsetDateTime.now();
        List<OutboxEvent> events = jdbc.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload, occurred_at
                FROM audit.outbox_events
                WHERE status = 'PENDING' AND event_type = ANY (?)
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY occurred_at
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """, statement -> {
            statement.setArray(1, statement.getConnection().createArrayOf("varchar", EVENT_TYPES.toArray()));
            statement.setObject(2, now);
        }, (rs, row) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                rs.getObject("occurred_at", OffsetDateTime.class)));

        for (OutboxEvent event : events) {
            try {
                route(event);
                jdbc.update("UPDATE audit.outbox_events SET status='PUBLISHED', published_at=?, updated_at=?, " +
                        "last_error=NULL, next_attempt_at=NULL WHERE id=?", now, now, event.id());
            } catch (RuntimeException processingFailure) {
                log.warn("Notification outbox processing failed for event {}", event.id());
                jdbc.update("UPDATE audit.outbox_events SET retry_count=retry_count+1, status='PENDING', " +
                        "last_error='NOTIFICATION_PROCESSING_FAILED', next_attempt_at=?, updated_at=? WHERE id=?",
                        now.plusMinutes(1), now, event.id());
            }
        }
    }

    private void route(OutboxEvent event) {
        try {
            Map<String, Object> values = mapper.readValue(event.payload(), new TypeReference<>() { });
            UUID userId;
            String referenceType;
            UUID referenceId = event.aggregateId();
            UUID sourceEventId = event.id();
            if ("PERMIT".equals(event.aggregateType())) {
                userId = uuid(values.get("beneficiaryUserId"));
                referenceType = "PERMIT";
                if ("PERMIT_EXPIRED".equals(event.eventType()) && values.get("validTo") != null) {
                    sourceEventId = NotificationEventIds.stable("PERMIT", event.aggregateId(),
                            event.eventType(), OffsetDateTime.parse(values.get("validTo").toString()));
                }
            } else if ("PAYMENT".equals(event.aggregateType())) {
                Payment payment = payments.findById(event.aggregateId()).orElse(null);
                if (payment == null) return;
                ParkingSession session = sessions.findById(payment.getParkingSessionId()).orElse(null);
                if (session == null) return;
                userId = session.getUserId();
                referenceType = "PAYMENT";
                values.put("parkingSessionId", session.getId());
                values.put("status", payment.getStatus());
                values.put("amount", payment.getAmount());
            } else {
                return;
            }
            generation.generate(userId, event.eventType(), sourceEventId, event.id(),
                    referenceType, referenceId, event.occurredAt(), values);
        } catch (JsonProcessingException invalidEvent) {
            throw new IllegalStateException("Invalid notification outbox event", invalidEvent);
        }
    }

    private UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        return UUID.fromString(String.valueOf(value));
    }

    private record OutboxEvent(UUID id, String aggregateType, UUID aggregateId,
                               String eventType, String payload, OffsetDateTime occurredAt) { }
}
