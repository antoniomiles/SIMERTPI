package ec.gob.simertpi.application.payments.webhook;

import ec.gob.simertpi.api.*;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.payments.*;
import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;
import ec.gob.simertpi.domain.payments.webhook.repository.PaymentWebhookRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.*;

@Service
public class PaymentWebhookService {
 private final PaymentWebhookRepository repository;private final PaymentProviderRegistry registry;
 private final List<PaymentWebhookVerifier> verifiers;private final PaymentIntegrationService integration;
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AuditService audit;
 public PaymentWebhookService(PaymentWebhookRepository repository,PaymentProviderRegistry registry,
  List<PaymentWebhookVerifier> verifiers,PaymentIntegrationService integration,JdbcTemplate jdbc,
  PlatformTransactionManager manager,AuditService audit) {
  this.repository=repository;this.registry=registry;this.verifiers=verifiers;this.integration=integration;
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.audit=audit;
 }
 public PaymentWebhook receive(String provider,Map<String,List<String>> headers,byte[] raw) {
  String digest=hash(raw);VerifiedPaymentEvent event;
  try {
   registry.resolve(provider);
   var matching=verifiers.stream().filter(v -> v.supports(provider)).toList();
   if(matching.size()!=1)throw new ForbiddenException("Webhook verification unavailable");
   event=matching.getFirst().verify(provider,headers,raw.clone()).orElseThrow(()->new ForbiddenException("Webhook verification rejected"));
   if(event.externalEventId()==null || event.externalEventId().isBlank() || event.externalEventId().length()>150 || event.paymentId()==null || event.result()==null || event.result().status()==null)
    throw new ForbiddenException("Webhook verification rejected");
  }catch(RuntimeException rejection) {
   var denied=new ForbiddenException("Webhook verification rejected");
   audit.failure("PAYMENT_WEBHOOK_REJECTED","PAYMENT_WEBHOOK",UUID.nameUUIDFromBytes((provider+digest).getBytes(java.nio.charset.StandardCharsets.UTF_8)),denied,digest);
   throw denied;
  }
  return tx.execute(s -> {
   jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",provider+":"+event.externalEventId());
   var prior=repository.findByProviderAndProviderEventId(provider,event.externalEventId());
   if(prior.isPresent()) {
    if(!digest.equals(prior.get().getPayload()))throw new IdempotencyConflictException();
    return prior.get();
   }
   integration.apply(provider,event.paymentId(),event.result(),event.providerOperationKey());
   var webhook=new PaymentWebhook();webhook.setId(UUID.randomUUID());webhook.setProvider(provider);
   webhook.setProviderEventId(event.externalEventId());webhook.setEventType(event.result().status().name());
   webhook.setPayload(digest); // Existing column stores fingerprint only, never raw webhook or headers.
   webhook.setProcessed(true);webhook.setProcessedAt(OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));webhook.setCreatedAt(OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
   return repository.save(webhook);
  });
 }
 private String hash(byte[] raw) {
  try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
 }
}
