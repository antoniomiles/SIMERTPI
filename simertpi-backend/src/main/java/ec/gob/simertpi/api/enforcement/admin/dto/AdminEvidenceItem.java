package ec.gob.simertpi.api.enforcement.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal evidence metadata for authorized administrative review. */
public record AdminEvidenceItem(
        UUID id,
        UUID violationId,
        String evidenceType,
        String contentType,
        Long fileSize,
        OffsetDateTime capturedAt,
        BigDecimal latitude,
        BigDecimal longitude,
        OffsetDateTime createdAt
) { }
