package ec.gob.simertpi.api.payments.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminPaymentItem(
        UUID id,
        UUID parkingSessionId,
        String provider,
        String amount,
        String currency,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt
) { }
