CREATE TABLE identity.inspector_activation_tokens (
    user_id UUID PRIMARY KEY REFERENCES identity.users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inspector_activation_expires_at
    ON identity.inspector_activation_tokens (expires_at);
