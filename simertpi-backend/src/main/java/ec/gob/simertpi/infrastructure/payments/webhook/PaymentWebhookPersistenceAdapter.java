package ec.gob.simertpi.infrastructure.payments.webhook;

import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;
import ec.gob.simertpi.domain.payments.webhook.repository.PaymentWebhookRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentWebhookPersistenceAdapter implements PaymentWebhookRepository {

    private final JpaPaymentWebhookRepository repository;

    public PaymentWebhookPersistenceAdapter(
            JpaPaymentWebhookRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public PaymentWebhook save(PaymentWebhook webhook) {
        return repository.save(webhook);
    }

    @Override
    public Optional<PaymentWebhook> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<PaymentWebhook> findByProviderAndProviderEventId(
            String provider,
            String providerEventId
    ) {
        return repository.findByProviderAndProviderEventId(
                provider,
                providerEventId
        );
    }
}