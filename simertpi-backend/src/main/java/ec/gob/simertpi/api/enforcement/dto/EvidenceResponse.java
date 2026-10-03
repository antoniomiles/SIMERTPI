package ec.gob.simertpi.api.enforcement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EvidenceResponse(
        UUID id,
        UUID violationId,
        String evidenceType,
        String originalFilename,
        String contentType,
        Long fileSize,
        String sha256Hash,
        OffsetDateTime capturedAt,
        BigDecimal latitude,
        BigDecimal longitude,
        OffsetDateTime createdAt
) { }
