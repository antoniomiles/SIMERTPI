CREATE TABLE audit.functional_audit_log (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id UUID,
    actor_name VARCHAR(150) NOT NULL,
    actor_role VARCHAR(100) NOT NULL,
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID,
    result VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(128),
    source_ip VARCHAR(64),
    user_agent VARCHAR(512),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_fingerprint VARCHAR(64),
    CONSTRAINT ck_functional_audit_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_functional_audit_occurred_at ON audit.functional_audit_log (occurred_at DESC);
CREATE INDEX idx_functional_audit_actor ON audit.functional_audit_log (actor_id, occurred_at DESC);
CREATE INDEX idx_functional_audit_resource ON audit.functional_audit_log (resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_functional_audit_action ON audit.functional_audit_log (action, occurred_at DESC);
CREATE INDEX idx_functional_audit_correlation ON audit.functional_audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;
CREATE UNIQUE INDEX uk_functional_audit_idempotent_operation
    ON audit.functional_audit_log (COALESCE(actor_id::text, actor_name), action, idempotency_fingerprint, resource_id)
    WHERE idempotency_fingerprint IS NOT NULL AND resource_id IS NOT NULL;

CREATE FUNCTION audit.reject_functional_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'functional audit log is append-only';
END;
$$;

CREATE TRIGGER trg_functional_audit_append_only
    BEFORE UPDATE OR DELETE ON audit.functional_audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_functional_audit_mutation();
