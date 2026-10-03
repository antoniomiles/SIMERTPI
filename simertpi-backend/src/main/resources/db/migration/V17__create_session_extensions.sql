CREATE TABLE parking.session_extensions (
    id UUID PRIMARY KEY,
    parking_session_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    additional_minutes INTEGER NOT NULL,
    previous_expected_end_at TIMESTAMPTZ NOT NULL,
    new_expected_end_at TIMESTAMPTZ NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_session_extensions_session
        FOREIGN KEY (parking_session_id)
        REFERENCES parking.parking_sessions (id),

    CONSTRAINT fk_session_extensions_payment
        FOREIGN KEY (payment_id)
        REFERENCES payments.payments (id),

    CONSTRAINT ck_session_extensions_minutes
        CHECK (additional_minutes > 0),

    CONSTRAINT ck_session_extensions_amount
        CHECK (amount >= 0),

    CONSTRAINT ck_session_extensions_dates
        CHECK (new_expected_end_at > previous_expected_end_at),

    CONSTRAINT ck_session_extensions_status
        CHECK (status IN (
            'PENDING_PAYMENT',
            'APPROVED',
            'DECLINED',
            'FAILED',
            'CANCELLED'
        )),

    CONSTRAINT uk_session_extensions_payment
        UNIQUE (payment_id)
);

CREATE INDEX idx_session_extensions_session
    ON parking.session_extensions (parking_session_id);

CREATE INDEX idx_session_extensions_status
    ON parking.session_extensions (status);

CREATE UNIQUE INDEX uk_session_extensions_pending
    ON parking.session_extensions (parking_session_id)
    WHERE status = 'PENDING_PAYMENT';
