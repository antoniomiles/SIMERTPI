CREATE TABLE identity.mobile_sessions (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES identity.users(id),
 access_hash varchar(64) NOT NULL UNIQUE,
 access_expires_at timestamptz NOT NULL,
 refresh_expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (refresh_expires_at >= access_expires_at)
);
CREATE INDEX mobile_sessions_user_idx ON identity.mobile_sessions(user_id);
CREATE TABLE identity.mobile_refresh_tokens (
 token_hash varchar(64) PRIMARY KEY,
 session_id uuid NOT NULL REFERENCES identity.mobile_sessions(id),
 used_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX mobile_refresh_session_idx ON identity.mobile_refresh_tokens(session_id);
