ALTER TABLE payments.payments ADD COLUMN provider_payment_id VARCHAR(150);
ALTER TABLE payments.payments ADD COLUMN provider_operation_key UUID;
ALTER TABLE payments.payments ADD COLUMN provider_operation_status VARCHAR(20);
ALTER TABLE payments.payments ADD CONSTRAINT ck_payment_provider_operation
 CHECK(provider_operation_status IN ('NOT_STARTED','REQUESTED','UNKNOWN','CONFIRMED'));
CREATE UNIQUE INDEX uk_payment_external_identity ON payments.payments(provider,provider_payment_id)
 WHERE provider_payment_id IS NOT NULL;
CREATE UNIQUE INDEX uk_payment_provider_operation ON payments.payments(provider_operation_key)
 WHERE provider_operation_key IS NOT NULL;
