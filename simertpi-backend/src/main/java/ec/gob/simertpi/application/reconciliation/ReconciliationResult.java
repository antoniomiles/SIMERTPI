package ec.gob.simertpi.application.reconciliation;
import java.time.OffsetDateTime;
import java.util.UUID;
public record ReconciliationResult(String resourceType, UUID resourceId, OffsetDateTime detectedAt,
 String issueCode, String status, String actionTaken, boolean requiresManualReview, String correlationId) { }
