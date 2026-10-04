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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentProviderHttpIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    private double metric(String name) { var meter=metrics.find(name).counter();return meter==null?0:meter.count(); }


    private static final String PASSWORD = "payment-flow-test";

    @LocalServerPort int port;
    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ParkingSessionRepository sessionRepository;
    @Autowired PaymentService paymentService;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    ec.gob.simertpi.infrastructure.payments.SandboxPaymentProvider sandbox;
    @Autowired ec.gob.simertpi.application.payments.PaymentIntegrationService integration;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ec.gob.simertpi.application.payments.PaymentProviderRegistry registry;
    @Autowired ec.gob.simertpi.application.reconciliation.PaymentReconciliationService reconciliation;
    @Autowired org.flywaydb.core.Flyway flyway;
    @org.springframework.boot.test.context.TestConfiguration
    static class VerifiedTestWebhook {
        @org.springframework.context.annotation.Bean
        ec.gob.simertpi.application.payments.webhook.PaymentWebhookVerifier testVerifier() {
            return new ec.gob.simertpi.application.payments.webhook.PaymentWebhookVerifier() {
                public boolean supports(String provider) {return "SANDBOX_STUB".equals(provider);}
                public java.util.Optional<ec.gob.simertpi.application.payments.webhook.VerifiedPaymentEvent> verify(String provider,java.util.Map<String,java.util.List<String>> headers,byte[] raw) {
                    if(headers.entrySet().stream().noneMatch(e->e.getKey().equalsIgnoreCase("X-Test-Verified") && e.getValue().contains("yes")))return java.util.Optional.empty();
                    String[] parts=new String(raw,java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
                    return java.util.Optional.of(new ec.gob.simertpi.application.payments.webhook.VerifiedPaymentEvent(parts[1],UUID.fromString(parts[0]),new ec.gob.simertpi.application.payments.PaymentProviderResult(ec.gob.simertpi.application.payments.ProviderPaymentStatus.valueOf(parts[2]),parts[3],parts[4])));
                }
            };
        }
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
    void cleanUp() {
        jdbc.update("DELETE FROM payments.webhooks WHERE provider_event_id LIKE ?",tag+"%");
        jdbc.update("DELETE FROM audit.reconciliation_findings WHERE resource_id=? OR resource_id IN (SELECT id FROM payments.payments WHERE parking_session_id=?)",sessionId,sessionId);
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


    private ResponseEntity<String> create(String key,String extra) {
        return restTemplate.withBasicAuth(username,PASSWORD).postForEntity("http://localhost:"+port+"/api/v1/payments",new HttpEntity<>("{\"parkingSessionId\":\""+sessionId+"\",\"paymentMethod\":\"TEST\""+extra+"}",headers(key)),String.class);
    }
    private HttpHeaders headers(String key) {var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.set("Idempotency-Key",key);return h;}
    private UUID created() throws Exception {var response=create("key","");assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);return UUID.fromString(objectMapper.readTree(response.getBody()).get("id").asText());}
    private void outcome(ec.gob.simertpi.application.payments.ProviderPaymentStatus status) {
        org.mockito.Mockito.doAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var request=(ec.gob.simertpi.application.payments.PaymentProviderRequest)call.getArgument(0);return new ec.gob.simertpi.application.payments.PaymentProviderResult(status,"sandbox-"+request.idempotencyKey(),"sandbox-"+request.paymentId());}).when(sandbox).createPayment(org.mockito.ArgumentMatchers.any());
    }
    private void query(ec.gob.simertpi.application.payments.ProviderPaymentStatus status) {
        org.mockito.Mockito.doAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var request=(ec.gob.simertpi.application.payments.PaymentProviderRequest)call.getArgument(1);return new ec.gob.simertpi.application.payments.PaymentProviderResult(status,"sandbox-"+request.idempotencyKey(),"sandbox-"+request.paymentId());}).when(sandbox).queryPayment(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    private String sessionState() {return jdbc.queryForObject("SELECT status FROM parking.parking_sessions WHERE id=?",String.class,sessionId);}
    private int audit(String action,UUID id) {return jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action=? AND resource_id=?",Integer.class,action,id);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ec.gob.simertpi.application.payments.ProviderPaymentStatus.class,names={"APPROVED","DECLINED","PENDING","FAILED"})
    void providerOutcomesOnlyActivateApproved(ec.gob.simertpi.application.payments.ProviderPaymentStatus status) throws Exception {
        String metricName="simertpi.payments."+status.name().toLowerCase(java.util.Locale.ROOT);double before=metric(metricName);
        outcome(status);UUID id=created();assertThat(metric(metricName)).isEqualTo(before+1);assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(status.name());assertThat(sessionState()).isEqualTo(status==ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED?"ACTIVE":"PENDING_PAYMENT");
    }
    @Test void backendAmountAndCurrencyCannotBeChangedByCitizen() throws Exception {
        jdbc.update("UPDATE parking.tariffs SET currency='EUR' WHERE id=?",tariffId);
        var response=create("key",",\"amount\":999,\"currency\":\"XXX\"");assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var captor=org.mockito.ArgumentCaptor.forClass(ec.gob.simertpi.application.payments.PaymentProviderRequest.class);org.mockito.Mockito.verify(sandbox).createPayment(captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("1.25");assertThat(captor.getValue().currency()).isEqualTo("EUR");
        assertThat(response.getBody()).doesNotContain("operationKey","operationStatus","payload","attempt","signature");
    }
    @Test void replayDoesNotCallProviderTwice() throws Exception {UUID id=created();var replay=create("key","");assertThat(objectMapper.readTree(replay.getBody()).get("id").asText()).isEqualTo(id.toString());org.mockito.Mockito.verify(sandbox,org.mockito.Mockito.times(1)).createPayment(org.mockito.ArgumentMatchers.any());assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);assertThat(audit("PAYMENT_PROVIDER_REQUESTED",id)).isEqualTo(1);}
    @Test void keyConflictIsRejectedBeforeProvider() throws Exception {created();var changed=restTemplate.withBasicAuth(username,PASSWORD).postForEntity("http://localhost:"+port+"/api/v1/payments",new HttpEntity<>("{\"parkingSessionId\":\""+sessionId+"\",\"paymentMethod\":\"DIFFERENT\"}",headers("key")),String.class);assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);org.mockito.Mockito.verify(sandbox,org.mockito.Mockito.times(1)).createPayment(org.mockito.ArgumentMatchers.any());}
    @Test void unconfiguredProviderReturnsControlledErrorWithoutCreatingPayment() {
        org.mockito.Mockito.doThrow(new ec.gob.simertpi.application.payments.PaymentProviderUnavailableException()).when(registry).selected();
        assertThat(create("key","").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);assertThat(paymentRepository.findByParkingSessionId(sessionId)).isEmpty();assertThat(sessionState()).isEqualTo("PENDING_PAYMENT");
    }
    @Test void uncertainCreationNeverDispatchesAgain() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("PRIVATE_FIXTURE_BODY")).when(sandbox).createPayment(org.mockito.ArgumentMatchers.any());UUID id=created();create("key","");
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo("PROCESSING");assertThat(paymentRepository.findById(id).orElseThrow().getProviderOperationStatus()).isEqualTo("UNKNOWN");org.mockito.Mockito.verify(sandbox,org.mockito.Mockito.times(1)).createPayment(org.mockito.ArgumentMatchers.any());
        assertThat(reconciliation.reconcile()).anyMatch(r->sessionId.equals(r.resourceId()) && r.requiresManualReview());
    }
    @Test void queryApprovalActivates() throws Exception {double count=metric("simertpi.payments.approved");UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED);integration.refresh(username,id);assertThat(metric("simertpi.payments.approved")).isEqualTo(count+1);assertThat(sessionState()).isEqualTo("ACTIVE");assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo("APPROVED");}
    @Test void queryPendingKeepsWaiting() throws Exception {UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.PENDING);integration.refresh(username,id);assertThat(sessionState()).isEqualTo("PENDING_PAYMENT");assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo("PENDING");assertThat(audit("PAYMENT_PROVIDER_PENDING",id)).isEqualTo(1);}
    @Test void queryUnknownNeverApproves() throws Exception {UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.UNKNOWN);integration.refresh(username,id);assertThat(sessionState()).isEqualTo("PENDING_PAYMENT");assertThat(reconciliation.reconcile()).anyMatch(r->sessionId.equals(r.resourceId()) && r.requiresManualReview());}
    @Test void queryCannotDowngradeApproved() throws Exception {outcome(ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED);UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.DECLINED);integration.refresh(username,id);assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo("APPROVED");}
    private void timeout(UUID id) {
        jdbc.update("UPDATE parking.parking_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours' WHERE id=?",sessionId);
        jdbc.update("UPDATE payments.payments SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours' WHERE id=?",id);
        jdbc.update("UPDATE payments.payment_attempts SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours' WHERE payment_id=?",id);
        reconciliation.cancelExpired(java.time.Duration.ofMinutes(30),OffsetDateTime.now());
    }
    @Test void lateQueryApprovalIsPreservedForManualReview() throws Exception {UUID id=created();timeout(id);query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED);integration.refresh(username,id);assertThat(sessionState()).isEqualTo("CANCELLED");assertThat(audit("PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED",id)).isEqualTo(1);assertThat(reconciliation.reconcile()).anyMatch(r->sessionId.equals(r.resourceId()) && r.requiresManualReview());}
    private String body(UUID id,String event,String status) {var payment=paymentRepository.findById(id).orElseThrow();return id+"|"+tag+event+"|"+status+"|"+payment.getProviderPaymentId()+"|sandbox-"+id+"|PRIVATE_FIXTURE_BODY";}
    private ResponseEntity<String> webhook(String provider,String raw,boolean verified) {var headers=new HttpHeaders();headers.setContentType(MediaType.TEXT_PLAIN);if(verified)headers.set("X-Test-Verified","yes");return restTemplate.postForEntity("http://localhost:"+port+"/api/v1/payments/webhooks/"+provider,new HttpEntity<>(raw,headers),String.class);}
    @Test void webhookWithoutVerifierIsRejected() {double count=metric("simertpi.payments.webhooks.rejected");assertThat(webhook("UNCONFIGURED","body",true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(metric("simertpi.payments.webhooks.rejected")).isEqualTo(count+1);}
    @Test void unknownWebhookProviderIsRejected() {assertThat(webhook("UNKNOWN","body",true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);}
    @Test void webhookNeedsSuccessfulVerification() throws Exception {UUID id=created();assertThat(webhook("SANDBOX_STUB",body(id,"event","APPROVED"),false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(sessionState()).isEqualTo("PENDING_PAYMENT");}
    @Test void verifiedWebhookIsIdempotentAndDoesNotStoreRawBody() throws Exception {
        UUID id=created();String raw=body(id,"event","APPROVED");var first=webhook("SANDBOX_STUB",raw,true);var replay=webhook("SANDBOX_STUB",raw,true);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(replay.getBody()).isEqualTo(first.getBody());assertThat(sessionState()).isEqualTo("ACTIVE");assertThat(audit("PAYMENT_APPROVED",id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM payments.webhooks WHERE provider_event_id=?",String.class,tag+"event")).doesNotContain("PRIVATE_FIXTURE_BODY").hasSize(64);
        assertThat(jdbc.queryForList("SELECT metadata::text FROM audit.functional_audit_log WHERE resource_id=?",String.class,id)).allMatch(v->!v.contains("PRIVATE_FIXTURE_BODY"));
    }
    @Test void conflictingWebhookReplayReturnsConflict() throws Exception {UUID id=created();String raw=body(id,"event","APPROVED");webhook("SANDBOX_STUB",raw,true);assertThat(webhook("SANDBOX_STUB",raw+"different",true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);}
    @Test void lateWebhookDoesNotReactivateCancelledSession() throws Exception {UUID id=created();timeout(id);assertThat(webhook("SANDBOX_STUB",body(id,"late","APPROVED"),true).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(sessionState()).isEqualTo("CANCELLED");assertThat(audit("PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED",id)).isEqualTo(1);}
    @Test void citizenCannotReadOrQueryOtherPayment() throws Exception {UUID id=created();org.junit.jupiter.api.Assertions.assertThrows(ec.gob.simertpi.api.ForbiddenException.class,()->integration.refresh(otherUsername,id));assertThat(restTemplate.withBasicAuth(otherUsername,PASSWORD).getForEntity("http://localhost:"+port+"/api/v1/payments/"+id,String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);}
    @Test void anonymousCannotCreateOrRefresh() throws Exception {UUID id=created();assertThat(restTemplate.postForEntity("http://localhost:"+port+"/api/v1/payments/"+id+"/refresh",null,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);}
    private void parallel(Runnable a,Runnable b) throws Exception {var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);try {var first=pool.submit(()->{ready.countDown();start.await();a.run();return 0;});var second=pool.submit(()->{ready.countDown();start.await();b.run();return 0;});assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();first.get(20,java.util.concurrent.TimeUnit.SECONDS);second.get(20,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
    @Test void concurrentCreateMakesOneExternalOperation() throws Exception {parallel(()->create("key",""),()->create("key",""));assertThat(paymentRepository.findByParkingSessionId(sessionId)).hasSize(1);org.mockito.Mockito.verify(sandbox,org.mockito.Mockito.times(1)).createPayment(org.mockito.ArgumentMatchers.any());}
    @Test void webhookAndQueryRaceApprovesOnce() throws Exception {UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED);parallel(()->webhook("SANDBOX_STUB",body(id,"race","APPROVED"),true),()->integration.refresh(username,id));assertThat(sessionState()).isEqualTo("ACTIVE");assertThat(audit("PAYMENT_APPROVED",id)).isEqualTo(1);}
    @Test void timeoutAndProviderApprovalRaceKeepsCoherentState() throws Exception {
        UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED);
        parallel(()->timeout(id),()->integration.refresh(username,id));
        String state=sessionState();assertThat(state).isIn("ACTIVE","CANCELLED");
        if("ACTIVE".equals(state))assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo("APPROVED");
        else {assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo("CANCELLED");assertThat(audit("PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED",id)).isEqualTo(1);}
    }
    @Test void externalCancellationCancelsPendingSession() throws Exception {
        outcome(ec.gob.simertpi.application.payments.ProviderPaymentStatus.CANCELLED);created();assertThat(sessionState()).isEqualTo("CANCELLED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ec.gob.simertpi.application.payments.ProviderPaymentStatus.class,names={"APPROVED","UNKNOWN"})
    void responseFromOldAttemptCannotApproveNewRetry(ec.gob.simertpi.application.payments.ProviderPaymentStatus lateStatus) throws Exception {
        UUID id=created();query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.FAILED);integration.refresh(username,id);
        var before=paymentRepository.findById(id).orElseThrow();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Query fixture timeout");return new ec.gob.simertpi.application.payments.PaymentProviderResult(lateStatus,before.getProviderPaymentId(),"late-reference");}).when(sandbox).queryPayment(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var pool=Executors.newSingleThreadExecutor();
        try {var old=pool.submit(()->integration.refresh(username,id));assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(create("new-key","").getStatusCode()).isEqualTo(HttpStatus.CREATED);release.countDown();old.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var current=paymentRepository.findById(id).orElseThrow();assertThat(current.getStatus()).isEqualTo("PENDING");assertThat(current.getProviderOperationKey()).isNotEqualTo(before.getProviderOperationKey());assertThat(current.getProviderPaymentId()).isNotEqualTo(before.getProviderPaymentId());assertThat(sessionState()).isEqualTo("PENDING_PAYMENT");assertThat(current.getProviderOperationStatus()).isEqualTo("CONFIRMED");assertThat(audit(lateStatus==ec.gob.simertpi.application.payments.ProviderPaymentStatus.APPROVED?"PAYMENT_LATE_APPROVAL_REVIEW_REQUIRED":"PAYMENT_PROVIDER_STATUS_REVIEW_REQUIRED",id)).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void oldUnknownWebhookCannotChangeNewAttempt() throws Exception {
        UUID id=created();String old=body(id,"old-unknown","UNKNOWN");query(ec.gob.simertpi.application.payments.ProviderPaymentStatus.FAILED);integration.refresh(username,id);
        assertThat(create("retry","").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(webhook("SANDBOX_STUB",old,true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentRepository.findById(id).orElseThrow().getProviderOperationStatus()).isEqualTo("CONFIRMED");assertThat(sessionState()).isEqualTo("PENDING_PAYMENT");
    }
    @Test void flywayValidatesV28() {flyway.validate();assertThat(Integer.parseInt(flyway.info().current().getVersion().toString())).isGreaterThanOrEqualTo(28);}
}
