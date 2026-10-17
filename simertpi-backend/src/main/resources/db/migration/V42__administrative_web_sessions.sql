CREATE TABLE identity.admin_web_sessions (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES identity.users(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE, security_stamp VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, absolute_expires_at TIMESTAMPTZ NOT NULL,
 idle_expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 CONSTRAINT ck_admin_session_expiry CHECK (absolute_expires_at > created_at AND idle_expires_at <= absolute_expires_at)
);
CREATE INDEX idx_admin_web_sessions_user ON identity.admin_web_sessions(user_id);
CREATE TABLE identity.admin_login_throttles (
 attempt_key VARCHAR(64) PRIMARY KEY, window_started_at TIMESTAMPTZ NOT NULL, attempts INTEGER NOT NULL
);
