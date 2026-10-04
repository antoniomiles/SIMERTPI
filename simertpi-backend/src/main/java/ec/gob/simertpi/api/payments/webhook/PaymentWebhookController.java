package ec.gob.simertpi.api.payments.webhook;
import ec.gob.simertpi.api.payments.webhook.dto.PaymentWebhookResponse;
import ec.gob.simertpi.application.payments.webhook.PaymentWebhookService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PaymentWebhookController {
 private final PaymentWebhookService service;
 public PaymentWebhookController(PaymentWebhookService service) {this.service=service;}
 @org.springframework.beans.factory.annotation.Value("${simertpi.payments.webhooks.max-body-bytes:65536}") private int maxBodyBytes;
 @PostMapping("/{provider}")
 public PaymentWebhookResponse receive(@PathVariable String provider,@RequestHeader HttpHeaders headers,jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
  byte[] raw=request.getInputStream().readNBytes(maxBodyBytes+1);
  if(raw.length>maxBodyBytes)throw new ec.gob.simertpi.api.InvalidRequestException("Webhook body too large");
  var event=service.receive(provider,headers,raw);
  return new PaymentWebhookResponse(event.getId(),event.getProvider(),event.getProviderEventId(),event.getEventType(),event.isProcessed(),event.getProcessedAt(),event.getCreatedAt());
 }
}
