package ec.gob.simertpi.api.payments.webhook.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentWebhookResponse(
        UUID id,
        String provider,
        String providerEventId,
        String eventType,
        boolean processed,
        OffsetDateTime processedAt,
        OffsetDateTime createdAt
) {
}
