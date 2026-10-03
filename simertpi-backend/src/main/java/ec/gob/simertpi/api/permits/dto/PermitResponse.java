package ec.gob.simertpi.api.permits.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PermitResponse(UUID id, UUID userId, UUID vehicleId, String plate, UUID zoneId,
                             String permitType, String status, OffsetDateTime validFrom,
                             OffsetDateTime validTo, String authorizationCode, String notes,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
