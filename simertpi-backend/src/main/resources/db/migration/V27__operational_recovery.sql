ALTER TABLE audit.outbox_events DROP CONSTRAINT ck_outbox_status;
ALTER TABLE audit.outbox_events ADD CONSTRAINT ck_outbox_status CHECK(status IN ('PENDING','PROCESSING','PUBLISHED','FAILED','DEAD'));
ALTER TABLE audit.outbox_events ADD COLUMN last_attempt_at TIMESTAMPTZ;
ALTER TABLE audit.outbox_events ADD COLUMN processing_token UUID;
CREATE INDEX idx_outbox_recovery ON audit.outbox_events(status,last_attempt_at,next_attempt_at);
CREATE TABLE audit.reconciliation_findings (
 id UUID PRIMARY KEY,
 resource_type VARCHAR(40) NOT NULL,
 resource_id UUID NOT NULL,
 issue_code VARCHAR(80) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('AUTO_RECOVERABLE','MANUAL_REVIEW_REQUIRED')),
 action_taken VARCHAR(80) NOT NULL,
 correlation_id VARCHAR(128),
 detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(resource_type,resource_id,issue_code)
);
CREATE INDEX idx_reconciliation_manual ON audit.reconciliation_findings(status,detected_at);
CREATE INDEX idx_pending_session_recovery ON parking.parking_sessions(created_at) WHERE status='PENDING_PAYMENT';
