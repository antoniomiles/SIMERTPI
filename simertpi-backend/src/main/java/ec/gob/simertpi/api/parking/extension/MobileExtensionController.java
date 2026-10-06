package ec.gob.simertpi.api.parking.extension;

import ec.gob.simertpi.api.*;
import ec.gob.simertpi.application.parking.ParkingSessionService;
import ec.gob.simertpi.application.parking.extension.ParkingSessionExtensionService;
import ec.gob.simertpi.application.parking.rules.*;
import ec.gob.simertpi.application.payments.*;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

/** Mobile facade: server quote, durable extension, then existing CP12 dispatch outside locks. */
@RestController
@RequestMapping("/api/v1/parking-sessions/{id}/extensions")
public class MobileExtensionController {
    private final ParkingSessionService parking;
    private final ParkingSessionRepository sessions;
    private final SessionExtensionRepository extensions;
    private final ParkingSessionExtensionService service;
    private final ParkingRulesService rules;
    private final PaymentProviderRegistry providers;
    private final PaymentIntegrationService payments;
    private final TransactionTemplate tx;
    public MobileExtensionController(ParkingSessionService parking, ParkingSessionRepository sessions,
            SessionExtensionRepository extensions, ParkingSessionExtensionService service, ParkingRulesService rules,
            PaymentProviderRegistry providers, PaymentIntegrationService payments, PlatformTransactionManager manager) {
        this.parking=parking; this.sessions=sessions; this.extensions=extensions; this.service=service;
        this.rules=rules; this.providers=providers; this.payments=payments; tx=new TransactionTemplate(manager);
    }
    @GetMapping("/quote")
    public ParkingRulesResult quote(@PathVariable UUID id, @RequestParam(required=false) Integer additionalMinutes,
            Authentication auth) {
        parking.assertSessionOwner(id,auth.getName());
        var session=parking.findById(id);
        if (!java.util.Set.of("ACTIVE","EXTENDED","EXPIRED","MAX_TIME_REACHED").contains(session.getStatus()))
            throw new IllegalArgumentException("Session is not eligible for extension");
        return rules.evaluateExtension(session,additionalMinutes,Instant.now());
    }
    @GetMapping("/options")
    public java.util.List<ParkingRulesResult> options(@PathVariable UUID id, Authentication auth) {
        parking.assertSessionOwner(id, auth.getName());
        var session = parking.findById(id);
        if (!java.util.Set.of("ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED").contains(session.getStatus())) return java.util.List.of();
        Instant now = Instant.now();
        var policy = rules.evaluateExtension(session, null, now);
        return ParkingDurationOptions.quotes(policy, minutes -> rules.evaluateExtension(session, minutes, now));
    }
    public record Request(@NotNull @Min(1) Integer additionalMinutes, @NotBlank @Size(max=50) String paymentMethod,
            @NotBlank @Size(max=128) String idempotencyKey, @NotNull @DecimalMin("0.0") BigDecimal expectedAmount,
            @NotNull OffsetDateTime expectedEndAt) { }
    @ExceptionHandler(PaymentProviderUnavailableException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String,String>> unavailable() {
        return org.springframework.http.ResponseEntity.status(503).body(java.util.Map.of("code","PAYMENT_PROVIDER_UNAVAILABLE"));
    }
    @PostMapping("/mobile")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public SessionExtension create(@PathVariable UUID id,@Valid @RequestBody Request request, Authentication auth) {
        parking.assertSessionOwner(id,auth.getName());
        String provider=providers.selected().providerCode();
        SessionExtension extension=tx.execute(status -> {
            var session=sessions.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Session not found"));
            for (var old:extensions.findByParkingSessionId(id)) {
                var payment=payments.owned(auth.getName(),old.getPaymentId());
                if (request.idempotencyKey().equals(payment.getIdempotencyKey())) {
                    if (!request.additionalMinutes().equals(old.getAdditionalMinutes())
                            || !request.paymentMethod().equals(payment.getPaymentMethod())
                            || request.expectedAmount().compareTo(old.getAmount())!=0
                            || !request.expectedEndAt().toInstant().equals(old.getNewExpectedEndAt().toInstant()))
                        throw new IdempotencyConflictException();
                    return old;
                }
            }
            var quote=rules.evaluateExtension(session,request.additionalMinutes(),Instant.now());
            if (!quote.extensionAllowed() || quote.calculatedAmount().compareTo(request.expectedAmount())!=0
                    || !quote.expiresAt().equals(request.expectedEndAt().toInstant()))
                throw new IllegalArgumentException("Extension conditions changed; request a new quote");
            return service.requestExtension(id,request.additionalMinutes(),provider,request.paymentMethod(),request.idempotencyKey());
        });
        payments.dispatchOwned(auth.getName(),extension.getPaymentId());
        return service.findById(extension.getId());
    }
}
