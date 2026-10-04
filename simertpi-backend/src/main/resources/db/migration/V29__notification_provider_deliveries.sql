ALTER TABLE configuration.notification_rules
 ADD COLUMN classification VARCHAR(20) NOT NULL DEFAULT 'INFORMATIONAL'
 CHECK (classification IN ('INFORMATIONAL','TRANSACTIONAL','SECURITY','LEGAL')),
 ADD COLUMN mandatory BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE notification.devices (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES identity.users(id),
 platform VARCHAR(10) NOT NULL CHECK(platform IN ('ANDROID','IOS','WEB')),
 token TEXT NOT NULL CHECK(length(token) BETWEEN 1 AND 4096),
 token_hash VARCHAR(64) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, disabled_at TIMESTAMPTZ
);
CREATE INDEX idx_devices_user_active ON notification.devices(user_id,active);
CREATE TABLE notification.preferences (
 user_id UUID NOT NULL REFERENCES identity.users(id),
 channel VARCHAR(20) NOT NULL CHECK(channel IN ('PUSH','WHATSAPP','EMAIL')),
 enabled BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,channel)
);
CREATE TABLE notification.deliveries (
 id UUID PRIMARY KEY, notification_id UUID NOT NULL REFERENCES notification.notifications(id) ON DELETE CASCADE,
 channel VARCHAR(20) NOT NULL CHECK(channel IN ('PUSH','WHATSAPP','EMAIL')),
 destination_reference VARCHAR(100) NOT NULL, device_id UUID REFERENCES notification.devices(id),
 provider_code VARCHAR(40) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('PENDING','PROCESSING','DELIVERED','TEMPORARY_FAILURE','PERMANENT_FAILURE','INVALID_DESTINATION','NO_DESTINATION','SUPPRESSED','UNKNOWN','DEAD')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0), external_message_id VARCHAR(150),
 last_attempt_at TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ, delivered_at TIMESTAMPTZ,
 error_code VARCHAR(64), processing_token UUID,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(notification_id,channel,destination_reference)
);
CREATE INDEX idx_deliveries_due ON notification.deliveries(status,next_attempt_at);
CREATE INDEX idx_deliveries_processing ON notification.deliveries(last_attempt_at) WHERE status='PROCESSING';
