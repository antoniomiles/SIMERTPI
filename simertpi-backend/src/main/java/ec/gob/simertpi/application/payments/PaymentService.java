package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.IdempotencyConflictException;
import ec.gob.simertpi.api.InvalidIdempotencyKeyException;
import ec.gob.simertpi.application.idempotency.IdempotencyRecord;
import ec.gob.simertpi.application.idempotency.IdempotencyStore;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;
import ec.gob.simertpi.domain.payments.entity.PaymentStatus;
import ec.gob.simertpi.domain.payments.repository.PaymentAttemptRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PaymentService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ec.gob.simertpi.application.operations.OperationalMetrics metrics;
    private void metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event event) { if(metrics!=null)metrics.event(event); }


    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final PaymentRepository paymentRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final TariffRepository tariffRepository;
    private final SessionExtensionRepository sessionExtensionRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final IdempotencyStore idempotencyStore;
    private final PaymentEventPublisher eventPublisher;
    private final ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;

    public PaymentService(
            PaymentRepository paymentRepository,
            ParkingSessionRepository parkingSessionRepository,
            TariffRepository tariffRepository,
            SessionExtensionRepository sessionExtensionRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyStore idempotencyStore,
            PaymentEventPublisher eventPublisher,
            ec.gob.simertpi.application.parking.rules.ParkingRulesService rules
    ) {
        this.rules = rules;
        this.paymentRepository = paymentRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.tariffRepository = tariffRepository;
        this.sessionExtensionRepository = sessionExtensionRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.idempotencyStore = idempotencyStore;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public Payment findById(UUID id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    @Transactional(readOnly = true)
    public java.util.List<Payment> findByParkingSessionId(UUID parkingSessionId) {
        return paymentRepository.findByParkingSessionId(parkingSessionId);
    }

    @Transactional
    @Audited(action = "PAYMENT_DECLINED", resourceType = "PAYMENT", resourceIdArgument = 0)
    public Payment decline(
            UUID paymentId,
            String failureReason
    ) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        lockAndRefresh(payment);
        transition(payment, PaymentStatus.DECLINED);

        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException(
                    "Failure reason is required"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();

        payment.setStatus("DECLINED");
        payment.setFailureReason(failureReason);
        payment.setPaidAt(null);
        payment.setUpdatedAt(now);
        Payment saved = paymentRepository.save(payment);
        updateLatestAttempt(payment, "DECLINED", null, failureReason);
        finishRejectedExtension(paymentId, "DECLINED", now);
        eventPublisher.publish(payment, "PAYMENT_DECLINED");
        return saved;
    }

    @Transactional
    @Audited(action = "PAYMENT_FAILED", resourceType = "PAYMENT", resourceIdArgument = 0)
    public Payment fail(UUID paymentId, String failureReason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        lockAndRefresh(payment);
        transition(payment, PaymentStatus.FAILED);

        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("Failure reason is required");
        }

        OffsetDateTime now = OffsetDateTime.now();

        payment.setStatus("FAILED");
        payment.setFailureReason(failureReason);
        payment.setPaidAt(null);
        payment.setUpdatedAt(now);

        Payment saved = paymentRepository.save(payment);
        updateLatestAttempt(payment, "FAILED", null, failureReason);
        finishRejectedExtension(paymentId, "FAILED", now);
        eventPublisher.publish(payment, "PAYMENT_FAILED");
        return saved;
    }

    @Transactional
    @Audited(action = "PAYMENT_APPROVED", resourceType = "PAYMENT", resourceIdArgument = 0)
    public Payment approve(
            UUID paymentId,
            String providerTransactionId
    ) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        transition(payment, PaymentStatus.APPROVED);

        if (providerTransactionId == null || providerTransactionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider transaction ID is required"
            );
        }

        ParkingSession session = parkingSessionRepository
                .findByIdForUpdate(payment.getParkingSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));

        if (entityManager != null) entityManager.refresh(payment);
        transition(payment, PaymentStatus.APPROVED);
        OffsetDateTime now = OffsetDateTime.now();

        /*
         * Pago de extension:
         * Si existe una extension asociada a este payment,
         * actualizamos la sesion cuando el pago queda aprobado.
         */
        java.util.Optional<SessionExtension> extensionOptional =
                sessionExtensionRepository.findByPaymentId(payment.getId());

        if (extensionOptional.isPresent()) {
            SessionExtension extension = extensionOptional.get();

            if (!"PENDING_PAYMENT".equals(extension.getStatus())) {
                throw new IllegalArgumentException(
                        "Session extension is not pending payment"
                );
            }

            if (!"ACTIVE".equals(session.getStatus())
                    && !"EXTENDED".equals(session.getStatus())
                    && !"EXPIRED".equals(session.getStatus())) {
                throw new IllegalArgumentException(
                        "Parking session is not eligible for extension approval"
                );
            }

            var quote = rules.evaluateExtension(session, extension.getAdditionalMinutes(), now.toInstant());
            if (!quote.extensionAllowed()) throw new IllegalArgumentException(quote.reasonCode());
            if (extension.getNewExpectedEndAt() == null || !extension.getNewExpectedEndAt().toInstant().equals(quote.expiresAt()))
                throw new IllegalArgumentException("EXTENSION_END_MISMATCH");

            session.setExpectedEndAt(extension.getNewExpectedEndAt());
            session.setExtensionCount(session.getExtensionCount() + 1);
            session.setStatus("EXTENDED");
            session.setUpdatedAt(now);

            metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event.EXTENSION_APPROVED);
            extension.setStatus("APPROVED");
            extension.setUpdatedAt(now);

            sessionExtensionRepository.save(extension);
            parkingSessionRepository.save(session);

            payment.setProviderTransactionId(providerTransactionId);
            payment.setStatus("APPROVED");
            payment.setPaidAt(now);
            payment.setFailureReason(null);
            payment.setUpdatedAt(now);

            Payment saved = paymentRepository.save(payment);
            updateLatestAttempt(payment, "APPROVED", providerTransactionId, null);
            // The extension event is written in the same transaction as the
            // approved payment and applied session end. Pending/rejected
            // attempts can therefore never announce a confirmed extension.
            eventPublisher.publish(payment, "PARKING_EXTENSION_CONFIRMED");
            return saved;
        }

        /*
         * Pago inicial:
         * La sesion debe encontrarse pendiente de pago.
         */
        if (!"PENDING_PAYMENT".equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Parking session is not pending payment"
            );
        }

        metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event.SESSION_ACTIVATED);
        session.setStatus("ACTIVE");
        session.setUpdatedAt(now);

        parkingSessionRepository.save(session);

        payment.setProviderTransactionId(providerTransactionId);
        payment.setStatus("APPROVED");
        payment.setPaidAt(now);
        payment.setFailureReason(null);
        payment.setUpdatedAt(now);

        Payment saved = paymentRepository.save(payment);
        updateLatestAttempt(payment, "APPROVED", providerTransactionId, null);
        eventPublisher.publish(payment, "PAYMENT_APPROVED");
        return saved;
    }
    @Transactional
    @Audited(action = "PAYMENT_APPROVED", resourceType = "PAYMENT")
    public Payment approveByProviderTransactionId(
            String providerTransactionId
    ) {
        if (providerTransactionId == null || providerTransactionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider transaction ID is required"
            );
        }

        Payment payment = paymentRepository
                .findByProviderTransactionId(providerTransactionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Payment not found")
                );

        return approve(payment.getId(), providerTransactionId);
    }

    @Transactional
    @Audited(action = "PAYMENT_CREATED", resourceType = "PAYMENT", idempotencyArgument = 3)
    public Payment createExtensionPayment(
            UUID parkingSessionId,
            String provider,
            String paymentMethod,
            String idempotencyKey,
            java.math.BigDecimal amount
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InvalidIdempotencyKeyException();
        }

        if (amount == null || amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Payment amount must be zero or greater");
        }

        ParkingSession session = parkingSessionRepository.findByIdForUpdate(parkingSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));

        if (!"ACTIVE".equals(session.getStatus())
                && !"EXTENDED".equals(session.getStatus()) && !"EXPIRED".equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Parking session is not active"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();
        String operation = "CREATE_EXTENSION_PAYMENT";
        String requestHash = sha256(parkingSessionId + "|" + provider + "|" + paymentMethod + "|" + amount);
        String scopedKey = session.getUserId() + ":" + operation + ":" + sha256(idempotencyKey.trim());
        if (!idempotencyStore.claim(scopedKey, operation, session.getUserId(), requestHash,
                now, now.plusHours(24))) {
            IdempotencyRecord prior = idempotencyStore.find(scopedKey);
            if (!session.getUserId().equals(prior.userId())
                    || !operation.equals(prior.operationType())
                    || !requestHash.equals(prior.requestHash())
                    || !"COMPLETED".equals(prior.status())
                    || !Integer.valueOf(201).equals(prior.responseStatus())) {
                throw new IdempotencyConflictException();
            }
            try {
                return paymentRepository.findById(UUID.fromString(prior.responseBody()))
                        .orElseThrow(IdempotencyConflictException::new);
            } catch (IllegalArgumentException malformed) {
                throw new IdempotencyConflictException();
            }
        }

        for (Payment pending : paymentRepository.findByParkingSessionId(session.getId())) {
            if ("PENDING".equals(pending.getStatus()) || "PROCESSING".equals(pending.getStatus())) {
                throw new IllegalArgumentException("A payment is already in progress for this session");
            }
        }

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setParkingSessionId(session.getId());
        payment.setProvider(provider);
        payment.setProviderOperationStatus("NOT_STARTED");
        payment.setProviderTransactionId(null);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmount(amount);
        Tariff sessionTariff = tariffRepository.findById(session.getTariffId()).orElse(null);
        payment.setCurrency(sessionTariff == null || sessionTariff.getCurrency() == null ? "USD" : sessionTariff.getCurrency());
        payment.setStatus("PENDING");
        payment.setPaymentMethod(paymentMethod);
        payment.setPaidAt(null);
        payment.setFailureReason(null);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);

        payment = paymentRepository.save(payment);
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setPaymentId(payment.getId());
        attempt.setAttemptNumber(1);
        attempt.setStatus("PENDING");
        attempt.setCreatedAt(now);
        paymentAttemptRepository.save(attempt);
        eventPublisher.publish(payment, "PAYMENT_CREATED");
        idempotencyStore.complete(scopedKey, 201, payment.getId().toString(), OffsetDateTime.now());
        return payment;
    }
    private void lockAndRefresh(Payment payment) {
        // Session first, same lock order as close/extension/reconciliation.
        parkingSessionRepository.findByIdForUpdate(payment.getParkingSessionId())
            .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
        if (entityManager != null) entityManager.refresh(payment);
    }

    private void finishRejectedExtension(UUID paymentId, String status, OffsetDateTime now) {
        sessionExtensionRepository.findByPaymentId(paymentId).ifPresent(extension -> {
            if ("PENDING_PAYMENT".equals(extension.getStatus())) {
                extension.setStatus(status);
                extension.setUpdatedAt(now);
                sessionExtensionRepository.save(extension);
            }
        });
    }

    private void transition(Payment payment, PaymentStatus target) {
        PaymentStatus current = PaymentStatus.fromCode(payment.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new IllegalArgumentException("Invalid payment state transition");
        }
    }

    private void updateLatestAttempt(Payment payment, String status,
                                     String providerTransactionId, String message) {
        paymentAttemptRepository.findTopByPaymentIdOrderByAttemptNumberDesc(payment.getId())
                .ifPresent(attempt -> {
                    attempt.setStatus(status);
                    attempt.setProviderTransactionId(providerTransactionId);
                    attempt.setResponseCode(status);
                    attempt.setResponseMessage(message);
                    paymentAttemptRepository.save(attempt);
                });
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
