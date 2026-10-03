package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.IdempotencyConflictException;
import ec.gob.simertpi.api.InvalidIdempotencyKeyException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.idempotency.IdempotencyRecord;
import ec.gob.simertpi.application.idempotency.IdempotencyStore;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;
import ec.gob.simertpi.domain.payments.repository.PaymentAttemptRepository;
import ec.gob.simertpi.domain.payments.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class CreatePaymentService {

    private static final String OPERATION = "CREATE_PAYMENT";
    private static final String NOT_CONFIGURED_PROVIDER = "UNCONFIGURED";
    private static final int CREATED = 201;
    private static final int IDEMPOTENCY_TTL_HOURS = 24;
    private static final List<String> IN_FLIGHT = List.of("PENDING", "PROCESSING");

    private final IdempotencyStore idempotencyStore;
    private final UserRepository userRepository;
    private final ParkingSessionRepository sessionRepository;
    private final TariffRepository tariffRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentEventPublisher eventPublisher;
    private final ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;

    public CreatePaymentService(IdempotencyStore idempotencyStore,
                                UserRepository userRepository,
                                ParkingSessionRepository sessionRepository,
                                TariffRepository tariffRepository,
                                PaymentRepository paymentRepository,
                                PaymentAttemptRepository attemptRepository,
                                PaymentEventPublisher eventPublisher,
                                ec.gob.simertpi.application.parking.rules.ParkingRulesService rules) {
        this.rules = rules;
        this.idempotencyStore = idempotencyStore;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.tariffRepository = tariffRepository;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    @Audited(action = "PAYMENT_CREATED", resourceType = "PAYMENT", idempotencyArgument = 1)
    public Payment create(String username, String suppliedKey, UUID parkingSessionId,
                          String paymentMethod) {
        if (suppliedKey == null || suppliedKey.isBlank() || suppliedKey.length() > 255) {
            throw new InvalidIdempotencyKeyException();
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        String key = user.getId() + ":" + OPERATION + ":" + sha256(suppliedKey.trim());
        String requestHash = sha256(parkingSessionId + "|" + paymentMethod);
        OffsetDateTime now = OffsetDateTime.now();

        if (!idempotencyStore.claim(key, OPERATION, user.getId(), requestHash,
                now, now.plusHours(IDEMPOTENCY_TTL_HOURS))) {
            IdempotencyRecord existing = idempotencyStore.find(key);
            if (!user.getId().equals(existing.userId())
                    || !OPERATION.equals(existing.operationType())
                    || !requestHash.equals(existing.requestHash())
                    || !"COMPLETED".equals(existing.status())
                    || !Integer.valueOf(CREATED).equals(existing.responseStatus())) {
                throw new IdempotencyConflictException();
            }
            try {
                return paymentRepository.findById(UUID.fromString(existing.responseBody()))
                        .orElseThrow(IdempotencyConflictException::new);
            } catch (IllegalArgumentException malformedResult) {
                throw new IdempotencyConflictException();
            }
        }

        ParkingSession session = sessionRepository.findByIdForUpdate(parkingSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
        if (!user.getId().equals(session.getUserId())) {
            throw new ForbiddenException("No tiene autorización para pagar esta sesión.");
        }
        if (!"PENDING_PAYMENT".equals(session.getStatus())) {
            throw new IllegalArgumentException("Parking session is not pending payment");
        }
        if (session.getEndedAt() != null) {
            throw new IllegalArgumentException("Parking session has already ended");
        }
        if (session.getExpectedEndAt() == null || !session.getExpectedEndAt().isAfter(now)) {
            throw new IllegalArgumentException("Parking session has expired");
        }
        if (session.getTariffId() == null) {
            throw new IllegalArgumentException("Parking session has no tariff");
        }
        Tariff tariff = tariffRepository.findById(session.getTariffId())
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
        if (!tariff.isActive() || tariff.getValidFrom().isAfter(now)
                || (tariff.getValidTo() != null && tariff.getValidTo().isBefore(now))) {
            throw new IllegalArgumentException("Tariff is not currently available");
        }
        // Preserve pre-CP10 sessions with unconfigured legacy tariffs; configured tariffs use the central calculation.
        BigDecimal amount = tariff.getRoundingMode() == null ? tariff.getAmount()
                : rules.calculateAmount(tariff, java.time.Duration.between(session.getStartedAt(), session.getExpectedEndAt()).toMinutes());
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        for (Payment existing : paymentRepository.findByParkingSessionId(session.getId())) {
            if (IN_FLIGHT.contains(existing.getStatus())) {
                throw new IllegalArgumentException("A payment is already in progress for this session");
            }
            if ("APPROVED".equals(existing.getStatus())) {
                throw new IllegalArgumentException("Parking session already has an approved payment");
            }
        }

        session.setTotalAmount(amount);
        session.setUpdatedAt(now);
        sessionRepository.save(session);

        List<Payment> payments = paymentRepository.findByParkingSessionId(session.getId());
        Payment payment = payments.stream()
                .filter(existing -> "FAILED".equals(existing.getStatus())
                        || "DECLINED".equals(existing.getStatus()))
                .findFirst()
                .orElse(null);
        boolean newPayment = payment == null;
        if (newPayment) {
            payment = new Payment();
            payment.setId(UUID.randomUUID());
            payment.setParkingSessionId(session.getId());
            payment.setCreatedAt(now);
            payment.setCurrency(tariff.getCurrency() == null ? "USD" : tariff.getCurrency());
        }
        payment.setProvider(NOT_CONFIGURED_PROVIDER);
        payment.setIdempotencyKey(key);
        payment.setAmount(amount);
        payment.setStatus("PENDING");
        payment.setPaymentMethod(paymentMethod);
        payment.setProviderTransactionId(null);
        payment.setFailureReason(null);
        payment.setPaidAt(null);
        payment.setUpdatedAt(now);
        payment = paymentRepository.save(payment);

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setPaymentId(payment.getId());
        attempt.setAttemptNumber(newPayment ? 1 : attemptRepository
                .findTopByPaymentIdOrderByAttemptNumberDesc(payment.getId())
                .map(previous -> previous.getAttemptNumber() + 1)
                .orElse(1));
        attempt.setStatus("PENDING");
        attempt.setCreatedAt(now);
        attemptRepository.save(attempt);
        eventPublisher.publish(payment, "PAYMENT_CREATED");

        idempotencyStore.complete(key, CREATED, payment.getId().toString(), OffsetDateTime.now());
        return payment;
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
