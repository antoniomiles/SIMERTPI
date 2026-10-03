package ec.gob.simertpi.domain.payments.webhook.repository;

import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;

import java.util.Optional;
import java.util.UUID;

public interface PaymentWebhookRepository {

    PaymentWebhook save(PaymentWebhook webhook);

    Optional<PaymentWebhook> findById(UUID id);

    Optional<PaymentWebhook> findByProviderAndProviderEventId(
            String provider,
            String providerEventId
    );
}