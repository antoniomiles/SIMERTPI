CREATE TABLE audit.idempotency_keys (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(150) NOT NULL,
    operation_type VARCHAR(100) NOT NULL,
    user_id UUID,
    request_hash VARCHAR(64),
    response_status INTEGER,
    response_body TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PROCESSING',
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_idempotency_key
        UNIQUE (idempotency_key),

    CONSTRAINT fk_idempotency_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users (id),

    CONSTRAINT ck_idempotency_status
        CHECK (status IN (
            'PROCESSING',
            'COMPLETED',
            'FAILED'
        ))
);

CREATE INDEX idx_idempotency_user_id
    ON audit.idempotency_keys (user_id);

CREATE INDEX idx_idempotency_status
    ON audit.idempotency_keys (status);

CREATE INDEX idx_idempotency_expires_at
    ON audit.idempotency_keys (expires_at);

CREATE INDEX idx_idempotency_operation
    ON audit.idempotency_keys (operation_type);
