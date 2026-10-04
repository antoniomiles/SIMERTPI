package ec.gob.simertpi.api.payments;

import ec.gob.simertpi.api.payments.dto.CreatePaymentRequest;
import ec.gob.simertpi.api.payments.dto.PaymentResponse;
import ec.gob.simertpi.application.payments.PaymentIntegrationService;
import ec.gob.simertpi.domain.payments.entity.Payment;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentIntegrationService paymentService;

    public PaymentController(PaymentIntegrationService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        Payment payment = paymentService.create(
                authentication.getName(),
                idempotencyKey,
                request.parkingSessionId(),
                request.paymentMethod()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(toResponse(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse status(Authentication authentication, @PathVariable java.util.UUID id) {
        return toResponse(paymentService.owned(authentication.getName(),id));
    }
    @PostMapping("/{id}/refresh")
    public PaymentResponse refresh(Authentication authentication, @PathVariable java.util.UUID id) {
        return toResponse(paymentService.refresh(authentication.getName(),id));
    }
    @ExceptionHandler(ec.gob.simertpi.application.payments.PaymentProviderUnavailableException.class)
    public ResponseEntity<java.util.Map<String,String>> unavailable() {
        return ResponseEntity.status(503).body(java.util.Map.of("code","PAYMENT_PROVIDER_UNAVAILABLE"));
    }
    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getParkingSessionId(),
                payment.getProvider(),
                payment.getProviderTransactionId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getPaymentMethod(),
                payment.getPaidAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
