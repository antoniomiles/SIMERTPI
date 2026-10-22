ALTER TABLE parking.tariffs
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by_user_id UUID REFERENCES identity.users(id),
    ADD COLUMN cancellation_reason VARCHAR(500),
    ADD CONSTRAINT ck_tariffs_cancellation_details CHECK (
        (cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancellation_reason IS NULL)
        OR (cancelled_at IS NOT NULL AND cancelled_by_user_id IS NOT NULL
            AND cancellation_reason IS NOT NULL AND length(btrim(cancellation_reason)) > 0 AND active = FALSE)
    );
