package ec.gob.simertpi.api.notifications.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(UUID id, String eventType, String channel, String title,
                                   String message, String status, String referenceType,
                                   UUID referenceId, OffsetDateTime createdAt,
                                   OffsetDateTime sentAt, OffsetDateTime readAt) { }
