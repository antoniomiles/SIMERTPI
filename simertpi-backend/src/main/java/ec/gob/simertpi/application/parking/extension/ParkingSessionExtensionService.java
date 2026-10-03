package ec.gob.simertpi.application.parking.extension;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.payments.PaymentService;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.extension.entity.SessionExtension;
import ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.payments.entity.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Transactional
public class ParkingSessionExtensionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_EXTENDED = "EXTENDED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_MAX_TIME_REACHED = "MAX_TIME_REACHED";

    private static final int GRACE_PERIOD_MINUTES = 10;

    private final SessionExtensionRepository sessionExtensionRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final TariffRepository tariffRepository;
    private final PaymentService paymentService;

    public ParkingSessionExtensionService(
            SessionExtensionRepository sessionExtensionRepository,
            ParkingSessionRepository parkingSessionRepository,
            TariffRepository tariffRepository,
            PaymentService paymentService
    ) {
        this.sessionExtensionRepository = sessionExtensionRepository;
        this.parkingSessionRepository = parkingSessionRepository;
        this.tariffRepository = tariffRepository;
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

        ParkingSession session = parkingSessionRepository.findById(parkingSessionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Parking session not found")
                );

        if (STATUS_MAX_TIME_REACHED.equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Parking session has reached maximum continuous parking time"
            );
        }

        if (!STATUS_ACTIVE.equals(session.getStatus())
                && !STATUS_EXTENDED.equals(session.getStatus())
                && !STATUS_EXPIRED.equals(session.getStatus())) {
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

        if (STATUS_EXPIRED.equals(session.getStatus())) {

            long minutesOverdue = Duration.between(
                    session.getExpectedEndAt(),
                    now
            ).toMinutes();

            if (minutesOverdue > GRACE_PERIOD_MINUTES) {
                throw new IllegalArgumentException(
                        "Parking session extension period has expired"
                );
            }
        }

        if (sessionExtensionRepository
                .findPendingByParkingSessionId(parkingSessionId)
                .isPresent()) {
            throw new IllegalArgumentException(
                    "Parking session already has a pending extension"
            );
        }

        if (session.getTariffId() == null) {
            throw new IllegalArgumentException(
                    "Parking session has no tariff"
            );
        }

        Tariff tariff = tariffRepository.findById(session.getTariffId())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Tariff not found")
                );

        if (!tariff.isActive()) {
            throw new IllegalArgumentException("Tariff is inactive");
        }

        if (tariff.getValidFrom().isAfter(now)) {
            throw new IllegalArgumentException("Tariff is not yet valid");
        }

        if (tariff.getValidTo() != null
                && tariff.getValidTo().isBefore(now)) {
            throw new IllegalArgumentException("Tariff has expired");
        }

        OffsetDateTime previousExpectedEndAt =
                session.getExpectedEndAt();

        OffsetDateTime newExpectedEndAt =
                previousExpectedEndAt.plusMinutes(additionalMinutes);

        if (tariff.getMaxContinuousMinutes() != null) {

            long totalMinutes = Duration.between(
                    session.getStartedAt(),
                    newExpectedEndAt
            ).toMinutes();

            if (totalMinutes > tariff.getMaxContinuousMinutes()) {
                throw new IllegalArgumentException(
                        "Extension exceeds maximum continuous parking time"
                );
            }
        }

        BigDecimal amountPerMinute = tariff.getAmount()
                .divide(
                        BigDecimal.valueOf(tariff.getDurationMinutes()),
                        10,
                        RoundingMode.HALF_UP
                );

        BigDecimal extensionAmount = amountPerMinute
                .multiply(BigDecimal.valueOf(additionalMinutes))
                .setScale(2, RoundingMode.HALF_UP);

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
