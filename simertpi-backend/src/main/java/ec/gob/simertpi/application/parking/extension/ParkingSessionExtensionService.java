package ec.gob.simertpi.application.parking.extension;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.payments.PaymentService;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Transactional
public class ParkingSessionExtensionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_EXTENDED = "EXTENDED";
    private static final String STATUS_MAX_TIME_REACHED = "MAX_TIME_REACHED";


    private final SessionExtensionRepository sessionExtensionRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;
    private final PaymentService paymentService;

    public ParkingSessionExtensionService(
            SessionExtensionRepository sessionExtensionRepository,
            ParkingSessionRepository parkingSessionRepository,
            ec.gob.simertpi.application.parking.rules.ParkingRulesService rules,
            PaymentService paymentService
    ) {
        this.sessionExtensionRepository = sessionExtensionRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.rules = rules;
        this.paymentService = paymentService;
    }

    @Transactional
    @Audited(action = "PARKING_SESSION_EXTENDED", resourceType = "PARKING_SESSION", resourceIdArgument = 0,
            idempotencyArgument = 4)
    public SessionExtension requestExtension(
            UUID parkingSessionId,
            Integer additionalMinutes,
            String provider,
            String paymentMethod,
            String idempotencyKey
    ) {
        if (additionalMinutes == null || additionalMinutes <= 0) {
            throw new IllegalArgumentException(
                    "Additional minutes must be greater than zero"
            );
        }

        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider is required");
        }

        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("Payment method is required");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }

        ParkingSession session = parkingSessionRepository.findByIdForUpdate(parkingSessionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Parking session not found")
                );

        for (SessionExtension previous : sessionExtensionRepository.findByParkingSessionId(parkingSessionId)) {
            Payment previousPayment = paymentService.findById(previous.getPaymentId());
            if (idempotencyKey.equals(previousPayment.getIdempotencyKey())) {
                if (!additionalMinutes.equals(previous.getAdditionalMinutes())
                        || !provider.equals(previousPayment.getProvider())
                        || !paymentMethod.equals(previousPayment.getPaymentMethod())) {
                    throw new ec.gob.simertpi.api.IdempotencyConflictException();
                }
                return previous;
            }
        }

        if (STATUS_MAX_TIME_REACHED.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Parking session has reached maximum continuous parking time"
            );
        }

        if (!STATUS_ACTIVE.equals(session.getStatus())
                && !STATUS_EXTENDED.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Parking session is not eligible for extension"
            );
        }

        if (session.getExpectedEndAt() == null) {
            throw new IllegalArgumentException(
                    "Parking session has no expected end time"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();

        if (sessionExtensionRepository
                .findPendingByParkingSessionId(parkingSessionId)
                .isPresent()) {
            throw new IllegalArgumentException(
                    "Parking session already has a pending extension"
            );
        }

        var quote = rules.evaluateExtension(session, additionalMinutes, now.toInstant());
        if (!quote.extensionAllowed()) throw new IllegalArgumentException(quote.reasonCode());
        OffsetDateTime previousExpectedEndAt = session.getExpectedEndAt();
        OffsetDateTime newExpectedEndAt = quote.expiresAt().atOffset(previousExpectedEndAt.getOffset());
        BigDecimal extensionAmount = quote.calculatedAmount();

        Payment payment = paymentService.createExtensionPayment(
                session.getId(),
                provider,
                paymentMethod,
                idempotencyKey,
                extensionAmount
        );

        SessionExtension extension = new SessionExtension();
        extension.setId(UUID.randomUUID());
        extension.setParkingSessionId(session.getId());
        extension.setPaymentId(payment.getId());
        extension.setAdditionalMinutes(additionalMinutes);
        extension.setPreviousExpectedEndAt(previousExpectedEndAt);
        extension.setNewExpectedEndAt(newExpectedEndAt);
        extension.setAmount(extensionAmount);
        extension.setStatus("PENDING_PAYMENT");
        extension.setCreatedAt(now);
        extension.setUpdatedAt(now);

        return sessionExtensionRepository.save(extension);
    }

    @Transactional(readOnly = true)
    public SessionExtension findById(UUID id) {
        return sessionExtensionRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Session extension not found"
                        )
                );
    }

    @Transactional(readOnly = true)
    public SessionExtension findByPaymentId(UUID paymentId) {
        return sessionExtensionRepository.findByPaymentId(paymentId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Session extension not found"
                        )
                );
    }
}
