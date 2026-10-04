package ec.gob.simertpi.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.application.payments.PaymentService;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"simertpi.notifications.outbox.enabled=false", "simertpi.control.scheduler.enabled=false", "simertpi.outbox.max-attempts=2", "simertpi.outbox.backoff-seconds=60", "simertpi.outbox.processing-timeout-seconds=60"})
class OperationalRecoveryPostgresIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "payment-flow-test";

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ParkingSessionRepository sessionRepository;
    @Autowired PaymentService paymentService;

    @Autowired ec.gob.simertpi.application.reconciliation.PaymentReconciliationService recovery;
    @Autowired ec.gob.simertpi.application.reconciliation.EvidenceReconciliationService evidenceRecovery;
    @Autowired ec.gob.simertpi.application.notifications.NotificationOutboxProcessor outbox;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired org.flywaydb.core.Flyway flyway;
    @Autowired ec.gob.simertpi.application.audit.AuditService auditService;
    @Autowired ec.gob.simertpi.application.enforcement.storage.ObjectStorage storage;
    private final UUID inspection=UUID.randomUUID(), violation=UUID.randomUUID(), evidenceId=UUID.randomUUID();
    private final java.util.List<String> objects=new java.util.ArrayList<>();
    private final java.util.List<UUID> events=new java.util.ArrayList<>();
    private static final java.nio.file.Path STORAGE;
    static {try {STORAGE=java.nio.file.Files.createTempDirectory("simertpi-cp11-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @org.springframework.test.context.DynamicPropertySource
    static void storageProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {registry.add("simertpi.evidence.storage.base-path",STORAGE::toString);}
    @org.junit.jupiter.api.AfterAll static void removeStorage() throws Exception {
        try(var paths=java.nio.file.Files.walk(STORAGE)) {for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
    }
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID streetId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID tariffId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private String username;
    private String otherUsername;

    @BeforeEach
    void setUp() {
        username = "payment-citizen-" + tag;
        otherUsername = "payment-other-" + tag;
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                userId, username, username + "@example.test", passwordEncoder.encode(PASSWORD),
                "Payment", "Test", now, now);
        jdbc.update("INSERT INTO identity.users(id, username, email, password_hash, first_name, last_name, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?)",
                otherUserId, otherUsername, otherUsername + "@example.test", passwordEncoder.encode(PASSWORD),
                "Other", "Citizen", now, now);
        UUID roleId = jdbc.queryForObject("SELECT id FROM identity.roles WHERE code = 'CITIZEN'", UUID.class);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", userId, roleId);
        jdbc.update("INSERT INTO identity.user_roles(user_id, role_id) VALUES (?, ?)", otherUserId, roleId);
        jdbc.update("INSERT INTO identity.vehicles(id, user_id, plate, active, created_at, updated_at) VALUES (?, ?, ?, true, ?, ?)",
                vehicleId, userId, "P" + tag.substring(0, 7), now, now);
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)", zoneId, "PZ-" + tag, "Payment zone");
        jdbc.update("INSERT INTO parking.streets(id, zone_id, code, name) VALUES (?, ?, ?, ?)", streetId, zoneId, "PS-" + tag, "Payment street");
        jdbc.update("INSERT INTO parking.parking_spaces(id, street_id, code, qr_code, space_number) VALUES (?, ?, ?, ?, ?)",
                spaceId, streetId, "PC-" + tag, "PQR-" + tag, tag.substring(0, 4));
        jdbc.update("INSERT INTO parking.tariffs(id, code, name, amount, duration_minutes, min_minutes, active, valid_from, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, true, ?, ?, ?)",
                tariffId, "PT-" + tag, "Payment tariff", new BigDecimal("1.25"), 60, 30,
                now.minusDays(1), now, now);
        jdbc.update("INSERT INTO parking.parking_sessions(id, user_id, vehicle_id, parking_space_id, tariff_id, started_at, expected_end_at, status, total_amount, extension_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', 0, 0, ?, ?)",
                sessionId, userId, vehicleId, spaceId, tariffId, now.minusMinutes(1), now.plusMinutes(59), now, now);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for(String key:objects)storage.delete(key);
        jdbc.update("DELETE FROM notification.notifications WHERE user_id IN (?,?)",userId,otherUserId);
        jdbc.update("DELETE FROM configuration.notification_rules WHERE code=?","CP11-"+tag);
        for(UUID id:events)jdbc.update("DELETE FROM audit.outbox_events WHERE id=?",id);
        jdbc.update("DELETE FROM enforcement.evidence WHERE violation_id=?",violation);
        jdbc.update("DELETE FROM enforcement.violations WHERE id=?",violation);
        jdbc.update("DELETE FROM enforcement.inspections WHERE id=?",inspection);
        jdbc.update("DELETE FROM audit.reconciliation_findings WHERE resource_id IN (?,?) OR resource_id IN (SELECT id FROM payments.payments WHERE parking_session_id=?)",sessionId,evidenceId,sessionId);
        jdbc.update("DELETE FROM audit.idempotency_keys WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_type = 'PAYMENT' AND aggregate_id IN (SELECT id FROM payments.payments WHERE parking_session_id = ?)", sessionId);
        jdbc.update("DELETE FROM payments.payment_attempts WHERE payment_id IN (SELECT id FROM payments.payments WHERE parking_session_id = ?)", sessionId);
        jdbc.update("DELETE FROM payments.payments WHERE parking_session_id = ?", sessionId);
        jdbc.update("DELETE FROM parking.parking_sessions WHERE id = ?", sessionId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM identity.user_roles WHERE user_id = ?", otherUserId);
        jdbc.update("DELETE FROM parking.parking_spaces WHERE id = ?", spaceId);
        jdbc.update("DELETE FROM parking.streets WHERE id = ?", streetId);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", zoneId);
        jdbc.update("DELETE FROM parking.tariffs WHERE id = ?", tariffId);
        jdbc.update("DELETE FROM identity.vehicles WHERE id = ?", vehicleId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", userId);
        jdbc.update("DELETE FROM identity.users WHERE id = ?", otherUserId);
    }


    private UUID payment(String state) {
        UUID id=UUID.randomUUID(); OffsetDateTime now=OffsetDateTime.now();
        jdbc.update("INSERT INTO payments.payments(id,parking_session_id,provider,idempotency_key,amount,currency,status,created_at,updated_at,paid_at,provider_transaction_id) VALUES(?,?,'TEST_ONLY',?,1.25,'USD',?,?,?,?,?)",
            id,sessionId,id.toString(),state,now.minusHours(1),now,"APPROVED".equals(state)?now:null,"APPROVED".equals(state)?"TEST-"+id:null);
        jdbc.update("UPDATE parking.parking_sessions SET total_amount=1.25 WHERE id=?",sessionId);
        jdbc.update("INSERT INTO payments.payment_attempts(id,payment_id,attempt_number,status,created_at) VALUES(?,?,1,?,CURRENT_TIMESTAMP-INTERVAL '1 hour')",UUID.randomUUID(),id,state);
        return id;
    }
    private void oldSession() {jdbc.update("UPDATE parking.parking_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",sessionId);}
    private String state() {return jdbc.queryForObject("SELECT status FROM parking.parking_sessions WHERE id=?",String.class,sessionId);}
    private int expire() {return recovery.cancelExpired(java.time.Duration.ofMinutes(30),OffsetDateTime.now());}
    private int audit(String action,UUID id) {return jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action=? AND resource_id=?",Integer.class,action,id);}
    private ec.gob.simertpi.application.reconciliation.ReconciliationResult result() {return recovery.reconcile().stream().filter(r->sessionId.equals(r.resourceId())).findFirst().orElseThrow();}
    private void parallel(Runnable first,Runnable second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {var a=pool.submit(()->{ready.countDown();start.await();first.run();return 0;});var b=pool.submit(()->{ready.countDown();start.await();second.run();return 0;});assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    }
    @Test void timeoutDisabledDoesNothing() {oldSession();payment("PENDING");assertThat(recovery.recoverPending()).isZero();assertThat(state()).isEqualTo("PENDING_PAYMENT");}
    @Test void expiresPendingPaymentAndAttemptAndReleasesSpace() {
        oldSession();UUID id=payment("PENDING");expire();assertThat(state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM parking.parking_sessions WHERE id=?",Boolean.class,sessionId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM payments.payments WHERE id=?",String.class,id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments.payment_attempts WHERE payment_id=?",String.class,id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.parking_sessions WHERE parking_space_id=? AND status IN ('PENDING_PAYMENT','ACTIVE','EXTENDED')",Integer.class,spaceId)).isZero();
        assertThat(audit("PAYMENT_PENDING_TIMEOUT",id)).isEqualTo(1);
    }
    @Test void expiresSessionWithoutPayment() {oldSession();expire();assertThat(state()).isEqualTo("CANCELLED");}
    @Test void expiresProcessingPayment() {oldSession();UUID id=payment("PROCESSING");expire();assertThat(jdbc.queryForObject("SELECT status FROM payments.payments WHERE id=?",String.class,id)).isEqualTo("CANCELLED");}
    @Test void recentSessionIsRetained() {payment("PENDING");expire();assertThat(state()).isEqualTo("PENDING_PAYMENT");}
    @Test void recentAttemptRetainsItsWindow() {oldSession();UUID id=payment("PENDING");jdbc.update("UPDATE payments.payments SET created_at=CURRENT_TIMESTAMP WHERE id=?",id);expire();assertThat(state()).isEqualTo("PENDING_PAYMENT");}
    @Test void freshRetryOfOldPaymentRetainsConfiguredWindow() {
        oldSession();UUID id=payment("PENDING");jdbc.update("INSERT INTO payments.payment_attempts(id,payment_id,attempt_number,status,created_at) VALUES(?,?,2,'PENDING',CURRENT_TIMESTAMP)",UUID.randomUUID(),id);
        expire();assertThat(state()).isEqualTo("PENDING_PAYMENT");
    }
    @Test void explicitlyEnabledTimeoutRecoversExpiredPending() {
        oldSession();payment("PENDING");
        var config=new ec.gob.simertpi.application.reconciliation.RecoveryConfiguration(new org.springframework.mock.env.MockEnvironment()
            .withProperty("simertpi.payments.pending-timeout.enabled","true").withProperty("simertpi.payments.pending-timeout.minutes","30"));
        var service=new ec.gob.simertpi.application.reconciliation.PaymentReconciliationService(jdbc,manager,
            new ec.gob.simertpi.application.reconciliation.ReconciliationFindings(jdbc,auditService),config);
        service.recoverPending();assertThat(state()).isEqualTo("CANCELLED");
    }
    @Test void approvedPaymentWinsBeforeTimeout() {oldSession();UUID id=payment("PENDING");paymentService.approve(id,"TEST-ONLY");expire();assertThat(state()).isEqualTo("ACTIVE");}
    @Test void lateApprovalCannotReactivateCancelledSession() {oldSession();UUID id=payment("PENDING");expire();org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->paymentService.approve(id,"LATE-TEST"));assertThat(state()).isEqualTo("CANCELLED");}
    @Test void repeatedTimeoutAuditsOnce() {oldSession();UUID id=payment("PENDING");expire();expire();assertThat(audit("PAYMENT_PENDING_TIMEOUT",id)).isEqualTo(1);assertThat(audit("SESSION_CANCELLED_BY_PAYMENT_TIMEOUT",sessionId)).isEqualTo(1);}
    @Test void concurrentTimeoutsHaveOneTransition() throws Exception {oldSession();UUID id=payment("PENDING");parallel(this::expire,this::expire);assertThat(audit("PAYMENT_PENDING_TIMEOUT",id)).isEqualTo(1);}
    @Test void timeoutVersusApprovalHasCoherentWinner() throws Exception {
        oldSession();UUID id=payment("PENDING");parallel(this::expire,()->{try{paymentService.approve(id,"RACE-TEST");}catch(IllegalArgumentException cancelled){}});
        String ps=jdbc.queryForObject("SELECT status FROM payments.payments WHERE id=?",String.class,id);
        assertThat(state()+":"+ps).isIn("ACTIVE:APPROVED","CANCELLED:CANCELLED");
    }
    @Test void invalidTimeoutDoesNotInventDefault() {org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->recovery.cancelExpired(java.time.Duration.ZERO,OffsetDateTime.now()));assertThat(state()).isEqualTo("PENDING_PAYMENT");}
    @Test void approvedPendingRecoveredWithConfirmedInternalMetadata() {payment("APPROVED");assertThat(result().status()).isEqualTo("AUTO_RECOVERABLE");assertThat(state()).isEqualTo("ACTIVE");assertThat(audit("PAYMENT_RECONCILIATION_RECOVERED",sessionId)).isEqualTo(1);}
    @Test void approvedCancelledRequiresReview() {payment("APPROVED");jdbc.update("UPDATE parking.parking_sessions SET status='CANCELLED' WHERE id=?",sessionId);assertThat(result().issueCode()).isEqualTo("PAYMENT_APPROVED_SESSION_CANCELLED");assertThat(state()).isEqualTo("CANCELLED");assertThat(audit("PAYMENT_RECONCILIATION_MANUAL_REVIEW",sessionId)).isEqualTo(1);}
    @Test void activeWithoutApprovedDetected() {jdbc.update("UPDATE parking.parking_sessions SET status='ACTIVE' WHERE id=?",sessionId);assertThat(result().issueCode()).isEqualTo("SESSION_ACTIVE_WITHOUT_APPROVED_PAYMENT");}
    @Test void multipleInitialApprovedPaymentsDetected() {payment("APPROVED");payment("APPROVED");assertThat(result().issueCode()).isEqualTo("MULTIPLE_APPROVED_PAYMENTS");assertThat(state()).isEqualTo("PENDING_PAYMENT");}
    @Test void incompleteApprovalIsNeverInferred() {UUID id=payment("APPROVED");jdbc.update("UPDATE payments.payments SET paid_at=NULL WHERE id=?",id);assertThat(result().requiresManualReview()).isTrue();assertThat(state()).isEqualTo("PENDING_PAYMENT");}
    @Test void manualFindingCanBecomeRecoveredWithoutSuppressingRecoveryAudit() {
        UUID id=payment("APPROVED");jdbc.update("UPDATE payments.payments SET paid_at=NULL WHERE id=?",id);
        assertThat(result().requiresManualReview()).isTrue();jdbc.update("UPDATE payments.payments SET paid_at=CURRENT_TIMESTAMP WHERE id=?",id);
        assertThat(result().status()).isEqualTo("AUTO_RECOVERABLE");assertThat(audit("PAYMENT_RECONCILIATION_RECOVERED",sessionId)).isEqualTo(1);
    }
    @Test void expiredApprovedSessionNeedsReviewRatherThanLateActivation() {
        payment("APPROVED");jdbc.update("UPDATE parking.parking_sessions SET started_at=CURRENT_TIMESTAMP-INTERVAL '2 hours',expected_end_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",sessionId);
        assertThat(result().requiresManualReview()).isTrue();assertThat(state()).isEqualTo("PENDING_PAYMENT");
    }
    @Test void consistentScanHasNoAuditNoise() {payment("PENDING");assertThat(result().status()).isEqualTo("CONSISTENT");assertThat(audit("PAYMENT_RECONCILIATION_MANUAL_REVIEW",sessionId)).isZero();}
    @Test void repeatedReconciliationIsIdempotent() {payment("APPROVED");result();assertThat(result().status()).isEqualTo("CONSISTENT");assertThat(audit("PAYMENT_RECONCILIATION_RECOVERED",sessionId)).isEqualTo(1);}
    @Test void twoReconcilersRecoverOnce() throws Exception {payment("APPROVED");parallel(recovery::reconcile,recovery::reconcile);assertThat(audit("PAYMENT_RECONCILIATION_RECOVERED",sessionId)).isEqualTo(1);}
    private UUID event(String status,String payload) {
        UUID id=UUID.randomUUID();events.add(id);
        jdbc.update("INSERT INTO audit.outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at,last_attempt_at) VALUES(?,'PERMIT',?,'PERMIT_CREATED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,id,payload,status);return id;
    }
    private String eventState(UUID id) {return jdbc.queryForObject("SELECT status FROM audit.outbox_events WHERE id=?",String.class,id);}
    private int attempts(UUID id) {return jdbc.queryForObject("SELECT retry_count FROM audit.outbox_events WHERE id=?",Integer.class,id);}
    @Test void pendingOutboxPublishes() {UUID id=event("PENDING","{\"beneficiaryUserId\":\""+UUID.randomUUID()+"\"}");outbox.processPending();assertThat(eventState(id)).isEqualTo("PUBLISHED");assertThat(attempts(id)).isEqualTo(1);}
    @Test void failedOutboxIncrementsAttemptAndSanitizesError() {UUID id=event("PENDING","secret-invalid-payload");outbox.processPending();assertThat(eventState(id)).isEqualTo("FAILED");assertThat(attempts(id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT last_error FROM audit.outbox_events WHERE id=?",String.class,id)).isEqualTo("NOTIFICATION_PROCESSING_FAILED");}
    @Test void retryRespectsNextAttempt() {UUID id=event("PENDING","bad");outbox.processPending();outbox.processPending();assertThat(attempts(id)).isEqualTo(1);}
    @Test void retryLimitMakesDeadAndAuditsOnce() {UUID id=event("PENDING","bad");outbox.processPending();jdbc.update("UPDATE audit.outbox_events SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",id);outbox.processPending();outbox.processPending();assertThat(eventState(id)).isEqualTo("DEAD");assertThat(attempts(id)).isEqualTo(2);assertThat(audit("OUTBOX_RETRY_EXHAUSTED",id)).isEqualTo(1);}
    @Test void staleProcessingRecoveredOnce() {UUID id=event("PROCESSING","{}");jdbc.update("UPDATE audit.outbox_events SET last_attempt_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE id=?",id);outbox.recoverStale();outbox.recoverStale();assertThat(eventState(id)).isEqualTo("FAILED");assertThat(audit("OUTBOX_STALE_PROCESSING_RECOVERED",id)).isEqualTo(1);}
    @Test void staleExhaustedProcessingBecomesDeadForManualReview() {
        UUID id=event("PROCESSING","{}");jdbc.update("UPDATE audit.outbox_events SET retry_count=2,last_attempt_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE id=?",id);
        assertThat(outbox.recoverStale()).anyMatch(r->id.equals(r.resourceId()) && r.requiresManualReview());assertThat(eventState(id)).isEqualTo("DEAD");
    }
    @Test void legacyExhaustedFailedRecordDoesNotStayAbandoned() {
        UUID id=event("FAILED","{}");jdbc.update("UPDATE audit.outbox_events SET retry_count=2 WHERE id=?",id);
        outbox.recoverStale();assertThat(eventState(id)).isEqualTo("DEAD");assertThat(audit("OUTBOX_RETRY_EXHAUSTED",id)).isEqualTo(1);
    }
    @Test void liveProcessingRemainsUntouched() {UUID id=event("PROCESSING","{}");outbox.recoverStale();assertThat(eventState(id)).isEqualTo("PROCESSING");}
    @Test void failureDoesNotStopBatch() {UUID bad=event("PENDING","bad");UUID good=event("PENDING","{\"beneficiaryUserId\":\""+UUID.randomUUID()+"\"}");outbox.processPending();assertThat(eventState(bad)).isEqualTo("FAILED");assertThat(eventState(good)).isEqualTo("PUBLISHED");}
    @Test void concurrentWorkersClaimOnce() throws Exception {UUID id=event("PENDING","{\"beneficiaryUserId\":\""+UUID.randomUUID()+"\"}");parallel(outbox::processPending,outbox::processPending);assertThat(eventState(id)).isEqualTo("PUBLISHED");assertThat(attempts(id)).isEqualTo(1);}
    @Test void concurrentRetriesDoNotDuplicateAttemptsOrExhaustionAudit() throws Exception {
        UUID id=event("PENDING","bad");outbox.processPending();jdbc.update("UPDATE audit.outbox_events SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",id);
        parallel(outbox::processPending,outbox::processPending);assertThat(attempts(id)).isEqualTo(2);assertThat(audit("OUTBOX_RETRY_EXHAUSTED",id)).isEqualTo(1);
    }
    @Test void concurrentWorkersDoNotDuplicateGeneratedNotification() throws Exception {
        UUID rule=UUID.randomUUID();
        jdbc.update("INSERT INTO configuration.notification_rules(id,code,event_type,channel,minutes_before,enabled,title_template,message_template,valid_from) VALUES(?,?,'PERMIT_CREATED','PUSH',0,true,'Test','Test',CURRENT_TIMESTAMP-INTERVAL '1 day')",rule,"CP11-"+tag);
        UUID id=event("PENDING","{\"beneficiaryUserId\":\""+userId+"\"}");parallel(outbox::processPending,outbox::processPending);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE outbox_event_id=? AND rule_id=?",Integer.class,id,rule)).isEqualTo(1);
        assertThat(attempts(id)).isEqualTo(1);
    }
    private String evidence(boolean metadata,boolean object,byte[] bytes) throws Exception {
        OffsetDateTime now=OffsetDateTime.now();
        jdbc.update("INSERT INTO enforcement.inspections(id,inspector_id,parking_space_id,observed_at,result) VALUES(?,?,?,?,'VIOLATION')",inspection,userId,spaceId,now);
        jdbc.update("INSERT INTO enforcement.violations(id,inspection_id,inspector_id,parking_space_id,violation_type,occurred_at) VALUES(?,?,?,?,'NO_PAYMENT',?)",violation,inspection,userId,spaceId,now);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String key="violations/"+violation+"/"+hash+".pdf";objects.add(key);
        if(object)storage.putIfAbsent(key,bytes,"application/pdf");
        if(metadata)jdbc.update("INSERT INTO enforcement.evidence(id,violation_id,evidence_type,storage_key,sha256_hash,file_size,captured_at) VALUES(?,?,'DOCUMENT',?,?,?,?)",evidenceId,violation,key,hash,bytes.length,now);
        return key;
    }
    private ec.gob.simertpi.application.reconciliation.ReconciliationResult evidenceResult() {return evidenceRecovery.reconcile().stream().filter(r->evidenceId.equals(r.resourceId())).findFirst().orElseThrow();}
    @Test void validEvidenceIsConsistentAndSilent() throws Exception {evidence(true,true,new byte[]{1,2,3});assertThat(evidenceResult().status()).isEqualTo("CONSISTENT");assertThat(audit("EVIDENCE_INCONSISTENCY_DETECTED",evidenceId)).isZero();}
    @Test void metadataWithoutObjectDetected() throws Exception {evidence(true,false,new byte[]{1});assertThat(evidenceResult().issueCode()).isEqualTo("METADATA_WITHOUT_OBJECT");}
    @Test void orphanObjectDetectedAndNeverDeleted() throws Exception {String key=evidence(false,true,new byte[]{1});assertThat(evidenceRecovery.reconcile()).anyMatch(r->r.issueCode().equals("OBJECT_WITHOUT_METADATA"));assertThat(storage.exists(key)).isTrue();}
    @Test void invalidLegacyStorageReferenceIsClassifiedAndScanContinues() throws Exception {
        evidence(true,false,new byte[]{1});jdbc.update("UPDATE enforcement.evidence SET storage_key='../unsafe-reference' WHERE id=?",evidenceId);
        assertThat(evidenceResult().issueCode()).isEqualTo("INVALID_STORAGE_REFERENCE");assertThat(evidenceResult().requiresManualReview()).isTrue();
    }
    @Test void hashMismatchDetected() throws Exception {evidence(true,true,new byte[]{1});jdbc.update("UPDATE enforcement.evidence SET sha256_hash=? WHERE id=?","0".repeat(64),evidenceId);assertThat(evidenceResult().issueCode()).isEqualTo("HASH_MISMATCH");}
    @Test void sizeMismatchDetected() throws Exception {evidence(true,true,new byte[]{1});jdbc.update("UPDATE enforcement.evidence SET file_size=2 WHERE id=?",evidenceId);assertThat(evidenceResult().issueCode()).isEqualTo("SIZE_MISMATCH");}
    @Test void diagnosticsDoNotModifyMetadataOrObject() throws Exception {String key=evidence(true,true,new byte[]{1});jdbc.update("UPDATE enforcement.evidence SET file_size=2 WHERE id=?",evidenceId);var before=jdbc.queryForMap("SELECT * FROM enforcement.evidence WHERE id=?",evidenceId);evidenceResult();assertThat(jdbc.queryForMap("SELECT * FROM enforcement.evidence WHERE id=?",evidenceId)).isEqualTo(before);try(var stored=storage.read(key)){assertThat(stored.stream().readAllBytes()).containsExactly((byte)1);}}
    @Test void repeatedEvidenceIssueAuditsOnceWithCorrelation() throws Exception {evidence(true,false,new byte[]{1});org.slf4j.MDC.put("correlationId","cp11-evidence-test");try {evidenceResult();evidenceResult();}finally{org.slf4j.MDC.clear();}assertThat(audit("EVIDENCE_INCONSISTENCY_DETECTED",evidenceId)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit.functional_audit_log WHERE resource_id=? AND action='EVIDENCE_INCONSISTENCY_DETECTED'",String.class,evidenceId)).isEqualTo("cp11-evidence-test");}
    @Test void concurrentEvidenceScansAuditOneFinding() throws Exception {
        evidence(true,false,new byte[]{1});parallel(evidenceRecovery::reconcile,evidenceRecovery::reconcile);
        assertThat(audit("EVIDENCE_INCONSISTENCY_DETECTED",evidenceId)).isEqualTo(1);
    }
    @Test void evidenceScanWaitsForUploadCommit() throws Exception {
        String key=evidence(false,false,new byte[]{1});var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var upload=pool.submit(()->{new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s->{jdbc.queryForList("SELECT id FROM enforcement.violations WHERE id=? FOR UPDATE",violation);try{storage.putIfAbsent(key,new byte[]{1},"application/pdf");locked.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException();String hash=key.substring(key.lastIndexOf('/')+1,key.length()-4);jdbc.update("INSERT INTO enforcement.evidence(id,violation_id,evidence_type,storage_key,sha256_hash,file_size,captured_at) VALUES(?,?,'DOCUMENT',?,?,1,CURRENT_TIMESTAMP)",evidenceId,violation,key,hash);}catch(Exception e){throw new RuntimeException(e);}});});assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var scan=pool.submit(evidenceRecovery::reconcile);release.countDown();upload.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(scan.get(10,java.util.concurrent.TimeUnit.SECONDS)).noneMatch(r->r.issueCode().equals("OBJECT_WITHOUT_METADATA"));}finally{release.countDown();pool.shutdownNow();}
    }
    private ResponseEntity<String> adminPost(String name,String suffix) {return restTemplate.withBasicAuth(name,PASSWORD).postForEntity("http://localhost:"+port+"/api/v1/admin/reconciliation/"+suffix,null,String.class);}
    private void role(String code) {jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?",userId);jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?",userId,code);}
    @Test void adminCanTriggerSafeReviewWithoutInternalLocations() throws Exception {evidence(true,false,new byte[]{1});role("SIMERTPI_ADMIN");for(String block:List.of("payments","evidence","outbox")){var response=adminPost(username,block);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getBody()).doesNotContain("storageKey","storage_key","violations/",STORAGE.toString(),"sha256");}}
    @Test void httpCorrelationIsPreservedByReconciliationAudit() {
        payment("APPROVED");jdbc.update("UPDATE parking.parking_sessions SET status='CANCELLED' WHERE id=?",sessionId);role("SIMERTPI_ADMIN");
        HttpHeaders headers=new HttpHeaders();headers.set("X-Correlation-ID","cp11-http-correlation");
        var response=restTemplate.withBasicAuth(username,PASSWORD).postForEntity("http://localhost:"+port+"/api/v1/admin/reconciliation/payments",new HttpEntity<>(null,headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("cp11-http-correlation");
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit.functional_audit_log WHERE resource_id=? AND action='PAYMENT_RECONCILIATION_MANUAL_REVIEW'",String.class,sessionId)).isEqualTo("cp11-http-correlation");
    }
    @Test void citizenCannotTriggerReconciliation() {assertThat(adminPost(username,"payments").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);}
    @Test void inspectorCannotTriggerReconciliation() {role("INSPECTOR");assertThat(adminPost(username,"evidence").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);}
    @Test void anonymousCannotTriggerReconciliation() {assertThat(restTemplate.postForEntity("http://localhost:"+port+"/api/v1/admin/reconciliation/outbox",null,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);}
    @Test void flywayValidatesIncludingV27() {flyway.validate();assertThat(Integer.parseInt(flyway.info().current().getVersion().toString())).isGreaterThanOrEqualTo(27);}
}
