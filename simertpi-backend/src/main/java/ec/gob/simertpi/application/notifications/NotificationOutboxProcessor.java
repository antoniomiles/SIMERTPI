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
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ec.gob.simertpi.application.reconciliation.RecoveryConfiguration config;
    private final ec.gob.simertpi.application.reconciliation.ReconciliationFindings findings;
    private final NotificationRetryPolicy retry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NotificationGenerationService generation;
    private final PaymentRepository payments;
    private final ParkingSessionRepository sessions;

    public NotificationOutboxProcessor(JdbcTemplate jdbc, ObjectMapper mapper,
                                        NotificationGenerationService generation,
                                        PaymentRepository payments, ParkingSessionRepository sessions,
                                        org.springframework.transaction.PlatformTransactionManager manager,
                                        ec.gob.simertpi.application.reconciliation.RecoveryConfiguration config,
                                        ec.gob.simertpi.application.reconciliation.ReconciliationFindings findings) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.config = config;
        this.retry = new NotificationRetryPolicy(config);
        this.findings = findings;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.generation = generation;
        this.payments = payments;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${simertpi.notifications.outbox.fixed-delay-ms:5000}")
    public void scheduledProcessing() {
        if(config.enabled("simertpi.notifications.outbox.enabled"))
            ec.gob.simertpi.application.reconciliation.ReconciliationSchedulers.runCorrelated(this::processPending);
    }

    public void processPending() {
        int max = Math.toIntExact(retry.maximumAttempts());
        retry.nextAttempt(OffsetDateTime.now(), 1);
        for(int batch=0;batch<100;batch++) {
            UUID token=UUID.randomUUID(); OffsetDateTime now=OffsetDateTime.now();
            OutboxEvent event=tx.execute(status -> {
                List<OutboxEvent> events=jdbc.query("""
                  SELECT id,aggregate_type,aggregate_id,event_type,payload,occurred_at FROM audit.outbox_events
                  WHERE status IN ('PENDING','FAILED') AND retry_count < ? AND event_type=ANY(?)
                    AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                  ORDER BY occurred_at LIMIT 1 FOR UPDATE SKIP LOCKED
                  """, statement -> {
                    statement.setInt(1,max);
                    statement.setArray(2,statement.getConnection().createArrayOf("varchar",EVENT_TYPES.toArray()));
                    statement.setObject(3,now);
                  }, (rs,row) -> new OutboxEvent(rs.getObject("id",UUID.class),rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id",UUID.class),rs.getString("event_type"),rs.getString("payload"),rs.getObject("occurred_at",OffsetDateTime.class)));
                if(events.isEmpty())return null;
                OutboxEvent claimed=events.getFirst();
                jdbc.update("UPDATE audit.outbox_events SET status='PROCESSING',retry_count=retry_count+1,last_attempt_at=?,processing_token=?,updated_at=? WHERE id=?",now,token,now,claimed.id());
                return claimed;
            });
            if(event==null)return;
            try {
                tx.executeWithoutResult(status -> {
                    var rows=jdbc.queryForList("SELECT id FROM audit.outbox_events WHERE id=? AND status='PROCESSING' AND processing_token=? FOR UPDATE",event.id(),token);
                    if(rows.isEmpty())return;
                    route(event);
                    jdbc.update("UPDATE audit.outbox_events SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,last_error=NULL,next_attempt_at=NULL,processing_token=NULL WHERE id=? AND processing_token=?",event.id(),token);
                });
            }catch(RuntimeException processingFailure) {
                ec.gob.simertpi.application.operations.OperationalMetrics.itemFailure();
                log.warn("event=outbox_processing result=FAILED scheduler=NOTIFICATION_OUTBOX");
                tx.executeWithoutResult(status -> {
                    var rows=jdbc.queryForList("SELECT retry_count FROM audit.outbox_events WHERE id=? AND status='PROCESSING' AND processing_token=? FOR UPDATE",event.id(),token);
                    if(rows.isEmpty())return;
                    int attempts=((Number)rows.getFirst().get("retry_count")).intValue();
                    boolean dead=attempts>=max;
                    jdbc.update("UPDATE audit.outbox_events SET status=?,last_error='NOTIFICATION_PROCESSING_FAILED',next_attempt_at=?,processing_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        dead?"DEAD":"FAILED",dead?null:retry.nextAttempt(OffsetDateTime.now(), attempts),event.id());
                    if(dead)findings.report("OUTBOX",event.id(),"RETRY_EXHAUSTED","MANUAL_REVIEW_REQUIRED","DEAD","OUTBOX_RETRY_EXHAUSTED");
                });
            }
        }
    }

    public java.util.List<ec.gob.simertpi.application.reconciliation.ReconciliationResult> recoverStale() {
        long timeout=config.positive("simertpi.outbox.processing-timeout-seconds");
        int max=Math.toIntExact(retry.maximumAttempts());
        retry.nextAttempt(OffsetDateTime.now(), 1);
        return tx.execute(status -> {
            var result=new java.util.ArrayList<ec.gob.simertpi.application.reconciliation.ReconciliationResult>();
            var rows=jdbc.queryForList("SELECT id,retry_count FROM audit.outbox_events WHERE (status='PROCESSING' AND COALESCE(last_attempt_at,updated_at)<=?) OR (status IN ('PENDING','FAILED') AND retry_count>=?) ORDER BY id FOR UPDATE SKIP LOCKED",OffsetDateTime.now().minusSeconds(timeout),max);
            for(var row:rows) {
                UUID id=(UUID)row.get("id");boolean dead=((Number)row.get("retry_count")).intValue()>=max;
                jdbc.update("UPDATE audit.outbox_events SET status=?,processing_token=NULL,last_error='STALE_PROCESSING',next_attempt_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",dead?"DEAD":"FAILED",dead?null:retry.nextAttempt(OffsetDateTime.now(), 1),id);
                result.add(findings.report("OUTBOX",id,dead?"RETRY_EXHAUSTED":"STALE_PROCESSING",dead?"MANUAL_REVIEW_REQUIRED":"AUTO_RECOVERABLE",dead?"DEAD":"RETRY_SCHEDULED",dead?"OUTBOX_RETRY_EXHAUSTED":"OUTBOX_STALE_PROCESSING_RECOVERED"));
            }
            return result;
        });
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
