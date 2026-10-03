package ec.gob.simertpi.infrastructure.payments.webhook;

import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaPaymentWebhookRepository
        extends JpaRepository<PaymentWebhook, UUID> {

    Optional<PaymentWebhook> findByProviderAndProviderEventId(
            String provider,
            String providerEventId
    );
}