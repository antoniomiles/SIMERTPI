CREATE TABLE payments.payments (
    id UUID PRIMARY KEY,
    parking_session_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_transaction_id VARCHAR(150),
    idempotency_key VARCHAR(150) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(30) NOT NULL,
    payment_method VARCHAR(50),
    paid_at TIMESTAMPTZ,
    failure_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payments_session
        FOREIGN KEY (parking_session_id)
        REFERENCES parking.parking_sessions (id),

    CONSTRAINT uk_payments_idempotency
        UNIQUE (idempotency_key),

    CONSTRAINT ck_payments_amount
        CHECK (amount >= 0),

    CONSTRAINT ck_payments_status
        CHECK (status IN (
            'PENDING',
            'PROCESSING',
            'APPROVED',
            'DECLINED',
            'FAILED',
            'REFUNDED',
            'CANCELLED'
        ))
);

CREATE TABLE payments.payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    provider_transaction_id VARCHAR(150),
    status VARCHAR(30) NOT NULL,
    response_code VARCHAR(50),
    response_message VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_attempts_payment
        FOREIGN KEY (payment_id)
        REFERENCES payments.payments (id)
        ON DELETE CASCADE,

    CONSTRAINT uk_payment_attempt_number
        UNIQUE (payment_id, attempt_number)
);

CREATE TABLE payments.webhooks (
    id UUID PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_webhooks_provider_event
        UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_payments_session_id
    ON payments.payments (parking_session_id);

CREATE INDEX idx_payments_status
    ON payments.payments (status);

CREATE INDEX idx_payments_provider_transaction
    ON payments.payments (provider_transaction_id);

CREATE INDEX idx_payment_attempts_payment_id
    ON payments.payment_attempts (payment_id);

CREATE INDEX idx_webhooks_processed
    ON payments.webhooks (processed);
