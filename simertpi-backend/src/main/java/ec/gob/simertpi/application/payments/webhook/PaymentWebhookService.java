package ec.gob.simertpi.application.payments.webhook;

import ec.gob.simertpi.application.payments.PaymentService;
import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;
import ec.gob.simertpi.domain.payments.webhook.repository.PaymentWebhookRepository;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.domain.payments.entity.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PaymentWebhookService {

    private final PaymentWebhookRepository webhookRepository;
    private final PaymentService paymentService;
    private final PaymentWebhookVerifier verifier;

    public PaymentWebhookService(
            PaymentWebhookRepository webhookRepository,
            PaymentService paymentService,
            PaymentWebhookVerifier verifier
    ) {
        this.webhookRepository = webhookRepository;
        this.paymentService = paymentService;
        this.verifier = verifier;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentWebhook> findByProviderAndEventId(
            String provider,
            String providerEventId
    ) {
        return webhookRepository.findByProviderAndProviderEventId(
                provider,
                providerEventId
        );
    }

    @Transactional
    public PaymentWebhook registerEvent(
            String provider,
            String providerEventId,
            String eventType,
            String providerTransactionId,
            UUID paymentId,
            String payload,
            String timestamp,
            String signature
    ) {
        if (!verifier.verify(provider, providerEventId, timestamp, signature, payload)) {
            throw new ForbiddenException("Webhook signature is invalid or not configured.");
        }
        Optional<PaymentWebhook> existing =
                webhookRepository.findByProviderAndProviderEventId(
                        provider,
                        providerEventId
                );

        if (existing.isPresent()) {
            return existing.get();
        }

        PaymentWebhook webhook = new PaymentWebhook();
        webhook.setId(UUID.randomUUID());
        webhook.setProvider(provider);
        webhook.setProviderEventId(providerEventId);
        webhook.setEventType(eventType);
        webhook.setPayload(payload);
        webhook.setProcessed(false);
        webhook.setProcessedAt(null);
        webhook.setCreatedAt(OffsetDateTime.now());

        PaymentWebhook savedWebhook = webhookRepository.save(webhook);

        Payment payment = paymentService.findById(paymentId);
        if (!provider.equals(payment.getProvider())) {
            throw new ForbiddenException("Webhook provider does not match the payment provider.");
        }

        switch (eventType) {
            case "PAYMENT_APPROVED" -> paymentService.approve(paymentId, providerTransactionId);
            case "PAYMENT_DECLINED" -> paymentService.decline(paymentId, "Provider declined payment");
            case "PAYMENT_FAILED" -> paymentService.fail(paymentId, "Provider reported payment failure");
            default -> throw new IllegalArgumentException("Unsupported payment event type");
        }
            savedWebhook.setProcessed(true);
            savedWebhook.setProcessedAt(OffsetDateTime.now());
            return webhookRepository.save(savedWebhook);
    }

    @Transactional
    public PaymentWebhook markAsProcessed(UUID webhookId) {
        PaymentWebhook webhook = webhookRepository.findById(webhookId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Webhook not found")
                );

        webhook.setProcessed(true);
        webhook.setProcessedAt(OffsetDateTime.now());

        return webhookRepository.save(webhook);
    }
}
