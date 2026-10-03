package ec.gob.simertpi.application.notifications;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class NotificationEventIds {
    private NotificationEventIds() { }

    public static UUID stable(String aggregateType, UUID aggregateId, String eventType, OffsetDateTime eventAt) {
        String key = aggregateType + ":" + aggregateId + ":" + eventType + ":" + eventAt.toInstant();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
