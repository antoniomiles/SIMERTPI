package ec.gob.simertpi.api.payments.webhook;

import ec.gob.simertpi.api.payments.webhook.dto.PaymentWebhookRequest;
import ec.gob.simertpi.api.payments.webhook.dto.PaymentWebhookResponse;
import ec.gob.simertpi.application.payments.webhook.PaymentWebhookService;
import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PaymentWebhookController {

    private final PaymentWebhookService webhookService;

    public PaymentWebhookController(
            PaymentWebhookService webhookService
    ) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<PaymentWebhookResponse> receiveWebhook(
            @PathVariable String provider,
            @RequestHeader("X-Provider-Timestamp") String timestamp,
            @RequestHeader("X-Provider-Signature") String signature,
            @Valid @RequestBody PaymentWebhookRequest request
    ) {
        PaymentWebhook webhook = webhookService.registerEvent(
                provider,
                request.providerEventId(),
                request.eventType(),
                request.providerTransactionId(),
                request.paymentId(),
                request.payload(),
                timestamp,
                signature
        );

        return ResponseEntity.ok(new PaymentWebhookResponse(webhook.getId(), webhook.getProvider(),
                webhook.getProviderEventId(), webhook.getEventType(), webhook.isProcessed(),
                webhook.getProcessedAt(), webhook.getCreatedAt()));
    }
}
