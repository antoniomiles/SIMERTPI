ALTER TABLE identity.mobile_sessions
    ADD COLUMN audience varchar(16) NOT NULL DEFAULT 'CITIZEN';

ALTER TABLE identity.mobile_sessions
    ADD CONSTRAINT mobile_sessions_audience_check
        CHECK (audience IN ('CITIZEN', 'MUNICIPAL'));
