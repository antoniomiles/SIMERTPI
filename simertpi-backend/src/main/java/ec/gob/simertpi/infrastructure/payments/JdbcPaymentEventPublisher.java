package ec.gob.simertpi.infrastructure.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.payments.PaymentEventPublisher;
import ec.gob.simertpi.domain.payments.entity.Payment;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcPaymentEventPublisher implements PaymentEventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPaymentEventPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(Payment payment, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "paymentId", payment.getId(),
                    "parkingSessionId", payment.getParkingSessionId(),
                    "status", payment.getStatus(),
                    "amount", payment.getAmount(),
                    "currency", payment.getCurrency(),
                    "provider", payment.getProvider(),
                    "correlationId", MDC.get("correlationId") == null ? "" : MDC.get("correlationId")
            ));
            jdbc.update("""
                    INSERT INTO audit.outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload, status, occurred_at, created_at, updated_at)
                    VALUES (?, 'PAYMENT', ?, ?, ?, 'PENDING', ?, ?, ?)
                    """, UUID.randomUUID(), payment.getId(), eventType, payload,
                    OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Could not serialize payment audit event", serializationFailure);
        }
    }
}
