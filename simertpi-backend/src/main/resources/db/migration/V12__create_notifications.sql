CREATE TABLE notification.notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    channel VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    reference_type VARCHAR(50),
    reference_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users (id),

    CONSTRAINT ck_notifications_channel
        CHECK (channel IN (
            'PUSH',
            'EMAIL',
            'SMS',
            'WHATSAPP',
            'IN_APP'
        )),

    CONSTRAINT ck_notifications_status
        CHECK (status IN (
            'PENDING',
            'SENT',
            'FAILED',
            'READ'
        ))
);

CREATE INDEX idx_notifications_user_id
    ON notification.notifications (user_id);

CREATE INDEX idx_notifications_status
    ON notification.notifications (status);

CREATE INDEX idx_notifications_created_at
    ON notification.notifications (created_at);

CREATE INDEX idx_notifications_reference
    ON notification.notifications (reference_type, reference_id);
