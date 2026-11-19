CREATE TABLE identity.admin_password_resets (
    user_id UUID PRIMARY KEY REFERENCES identity.users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    account_stamp CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE identity.admin_recovery_throttles (
    bucket_key CHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL
);

CREATE INDEX idx_admin_recovery_throttles_expiry
    ON identity.admin_recovery_throttles(expires_at);
