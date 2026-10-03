ALTER TABLE configuration.notification_rules
    DROP CONSTRAINT ck_notification_rules_channel;

ALTER TABLE configuration.notification_rules
    ADD CONSTRAINT ck_notification_rules_channel
    CHECK (channel IN ('PUSH', 'WHATSAPP', 'EMAIL'));

ALTER TABLE configuration.notification_rules
    DROP CONSTRAINT ck_notification_rules_event_type;

ALTER TABLE configuration.notification_rules
    ADD CONSTRAINT ck_notification_rules_event_type
    CHECK (event_type IN (
        'EXPIRATION', 'GRACE_PERIOD', 'AMONESTACION', 'EXCESS_11_30',
        'EXCESS_31_60', 'EXCESS_61_120', 'EXCESS_OVER_120',
        'IMMOBILIZATION', 'MAX_TIME_WARNING', 'MAX_TIME_REACHED',
        'PERMIT_CREATED', 'PERMIT_CANCELLED', 'PERMIT_EXPIRED',
        'PAYMENT_CREATED', 'PAYMENT_APPROVED', 'PAYMENT_DECLINED',
        'PAYMENT_FAILED', 'PAYMENT_CANCELLED_TIMEOUT'
    ));

ALTER TABLE notification.notifications
    ADD COLUMN source_event_id UUID,
    ADD COLUMN outbox_event_id UUID,
    ADD COLUMN rule_id UUID,
    ADD COLUMN recipient VARCHAR(255),
    ADD COLUMN provider_reference VARCHAR(255),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

ALTER TABLE notification.notifications
    ADD CONSTRAINT fk_notifications_rule
        FOREIGN KEY (rule_id) REFERENCES configuration.notification_rules (id),
    ADD CONSTRAINT fk_notifications_outbox_event
        FOREIGN KEY (outbox_event_id) REFERENCES audit.outbox_events (id),
    ADD CONSTRAINT ck_notifications_attempt_count CHECK (attempt_count >= 0);

CREATE UNIQUE INDEX uk_notifications_event_user_channel_rule
    ON notification.notifications (source_event_id, user_id, channel, rule_id)
    WHERE source_event_id IS NOT NULL AND rule_id IS NOT NULL;

CREATE INDEX idx_notifications_retry
    ON notification.notifications (status, next_attempt_at)
    WHERE status = 'FAILED';

ALTER TABLE audit.outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_retry
    ON audit.outbox_events (status, next_attempt_at, occurred_at)
    WHERE status = 'PENDING';
