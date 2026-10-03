package ec.gob.simertpi.api.payments.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID parkingSessionId,
        String provider,
        String providerTransactionId,
        BigDecimal amount,
        String currency,
        String status,
        String paymentMethod,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
