package ec.gob.simertpi.application.reconciliation;

import ec.gob.simertpi.application.audit.AuditService;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Durable diagnostics, deduplicated across actors and concurrent executions. */
@Service
public class ReconciliationFindings {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReconciliationFindings(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ReconciliationResult report(String type, UUID id, String issue, String status,
                                       String action, String auditAction) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!"CONSISTENT".equals(status)) {
            var changes = jdbc.query("""
                    INSERT INTO audit.reconciliation_findings
                      (id, resource_type, resource_id, issue_code, status, action_taken, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (resource_type, resource_id, issue_code) DO UPDATE
                    SET status=EXCLUDED.status, action_taken=EXCLUDED.action_taken,
                        correlation_id=EXCLUDED.correlation_id
                    WHERE (reconciliation_findings.status, reconciliation_findings.action_taken)
                      IS DISTINCT FROM (EXCLUDED.status, EXCLUDED.action_taken)
                    RETURNING id
                    """, (rs, row) -> rs.getObject(1, UUID.class), UUID.randomUUID(), type, id,
                    issue, status, action, MDC.get("correlationId"));
            if (!changes.isEmpty()) {
                audit.success(auditAction, type, id, null,
                        Map.of("issueCode", issue, "actionTaken", action));
            }
        }
        return new ReconciliationResult(type, id, now, issue, status, action,
                "MANUAL_REVIEW_REQUIRED".equals(status), MDC.get("correlationId"));
    }
}
