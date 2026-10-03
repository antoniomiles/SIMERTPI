package ec.gob.simertpi.application.idempotency;

import java.util.UUID;

public record IdempotencyRecord(
        UUID userId,
        String operationType,
        String requestHash,
        Integer responseStatus,
        String responseBody,
        String status
) {
}
