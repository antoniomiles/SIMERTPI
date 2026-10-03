package ec.gob.simertpi.application.idempotency;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface IdempotencyStore {
    boolean claim(String key, String operation, UUID userId, String requestHash,
                  OffsetDateTime now, OffsetDateTime expiresAt);
    IdempotencyRecord find(String key);
    void complete(String key, int responseStatus, String responseBody, OffsetDateTime now);
}
