ALTER TABLE identity.mobile_sessions ADD COLUMN authentication_method varchar(16) NOT NULL DEFAULT 'PASSWORD';
CREATE TABLE identity.device_credentials (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES identity.users(id),
 device_id uuid NOT NULL,
 public_key text NOT NULL,
 registered boolean NOT NULL DEFAULT false,
 revoked_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX device_credentials_owner ON identity.device_credentials(user_id);
CREATE TABLE identity.device_challenges (
 id uuid PRIMARY KEY,
 credential_id uuid NOT NULL REFERENCES identity.device_credentials(id),
 purpose varchar(8) NOT NULL CHECK (purpose IN ('REGISTER','LOGIN','UNLOCK')),
 payload text NOT NULL,
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX device_challenge_rate ON identity.device_challenges(credential_id, created_at);
