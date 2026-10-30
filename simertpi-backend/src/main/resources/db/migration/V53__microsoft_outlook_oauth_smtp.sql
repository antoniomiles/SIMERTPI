ALTER TABLE identity.admin_smtp_configuration
    DROP CONSTRAINT admin_smtp_configuration_provider_check;
-- MICROSOFT_OUTLOOK is 17 characters; V52 sized this column for the shorter providers.
ALTER TABLE identity.admin_smtp_configuration
    ALTER COLUMN provider TYPE VARCHAR(32);
ALTER TABLE identity.admin_smtp_configuration
    ADD CONSTRAINT ck_admin_smtp_configuration_provider
    CHECK (provider IN ('GMAIL', 'CUSTOM', 'MICROSOFT_OUTLOOK'));
ALTER TABLE identity.admin_smtp_configuration
    ALTER COLUMN credential_nonce DROP NOT NULL,
    ALTER COLUMN credential_ciphertext DROP NOT NULL;
ALTER TABLE identity.admin_smtp_configuration
    ADD CONSTRAINT ck_admin_smtp_credential_by_provider CHECK (
      (provider = 'MICROSOFT_OUTLOOK' AND credential_nonce IS NULL AND credential_ciphertext IS NULL)
      OR (provider <> 'MICROSOFT_OUTLOOK' AND credential_nonce IS NOT NULL AND credential_ciphertext IS NOT NULL)
    );

CREATE TABLE identity.admin_smtp_oauth_connection (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    state VARCHAR(24) NOT NULL CHECK (state IN ('CONNECTED', 'ACTIVE', 'REAUTH_REQUIRED', 'DISCONNECTED')),
    sender_email VARCHAR(255) NOT NULL,
    microsoft_subject VARCHAR(255) NOT NULL,
    access_token_nonce BYTEA CHECK (access_token_nonce IS NULL OR octet_length(access_token_nonce) = 12),
    access_token_ciphertext BYTEA CHECK (access_token_ciphertext IS NULL OR octet_length(access_token_ciphertext) >= 16),
    refresh_token_nonce BYTEA CHECK (refresh_token_nonce IS NULL OR octet_length(refresh_token_nonce) = 12),
    refresh_token_ciphertext BYTEA CHECK (refresh_token_ciphertext IS NULL OR octet_length(refresh_token_ciphertext) >= 16),
    access_expires_at TIMESTAMPTZ NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_refreshed_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    test_passed_at TIMESTAMPTZ,
    updated_by VARCHAR(150) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_admin_smtp_oauth_tokens CHECK (
      (state = 'DISCONNECTED' AND access_token_nonce IS NULL AND access_token_ciphertext IS NULL
       AND refresh_token_nonce IS NULL AND refresh_token_ciphertext IS NULL)
      OR (state <> 'DISCONNECTED' AND access_token_nonce IS NOT NULL AND access_token_ciphertext IS NOT NULL
          AND refresh_token_nonce IS NOT NULL AND refresh_token_ciphertext IS NOT NULL)
    )
);

CREATE TABLE identity.admin_smtp_oauth_attempt (
    id UUID PRIMARY KEY,
    state_hash CHAR(64) NOT NULL UNIQUE,
    nonce VARCHAR(128) NOT NULL,
    verifier_nonce BYTEA NOT NULL CHECK (octet_length(verifier_nonce) = 12),
    verifier_ciphertext BYTEA NOT NULL CHECK (octet_length(verifier_ciphertext) >= 16),
    actor VARCHAR(150) NOT NULL,
    admin_session_hash CHAR(64) NOT NULL,
    expected_sender VARCHAR(255) NOT NULL,
    bootstrap BOOLEAN NOT NULL DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_smtp_oauth_attempt_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_admin_smtp_oauth_attempt_expiry
    ON identity.admin_smtp_oauth_attempt (expires_at) WHERE consumed_at IS NULL;
CREATE UNIQUE INDEX uq_admin_smtp_oauth_bootstrap_attempt
    ON identity.admin_smtp_oauth_attempt ((bootstrap)) WHERE bootstrap AND consumed_at IS NULL;

CREATE TABLE identity.admin_smtp_oauth_test_attempt (
    id UUID PRIMARY KEY,
    test_kind VARCHAR(24) NOT NULL CHECK (test_kind IN ('CONNECTION', 'EMAIL')),
    result VARCHAR(16) NOT NULL CHECK (result IN ('STARTED', 'SUCCESS', 'FAILURE')),
    actor VARCHAR(150) NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_smtp_oauth_test_recent
    ON identity.admin_smtp_oauth_test_attempt (actor, test_kind, created_at DESC);

COMMENT ON TABLE identity.admin_smtp_oauth_connection IS
    'Microsoft delegated SMTP OAuth connection; tokens are AES-GCM encrypted and never returned by API.';
COMMENT ON TABLE identity.admin_smtp_oauth_attempt IS
    'One-use PKCE authorization attempts bound to a live administrator session; state is stored only as SHA-256.';
COMMENT ON TABLE identity.admin_smtp_oauth_test_attempt IS
    'Rate-limit and audit metadata for Microsoft OAuth SMTP tests; contains no tokens or message content.';
