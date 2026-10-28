-- Binary content is separate from evidence metadata so older metadata rows remain valid.
-- The temporary NULL evidence_id is allowed only while a transaction is uploading: the
-- application links it to the metadata row before commit. The composite foreign key
-- proves that the linked evidence belongs to the same violation and storage key.
CREATE TABLE enforcement.evidence_content (
    storage_key VARCHAR(500) PRIMARY KEY,
    violation_id UUID NOT NULL,
    evidence_id UUID UNIQUE,
    content BYTEA NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    sha256_hash VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_evidence_content_violation
        FOREIGN KEY (violation_id)
        REFERENCES enforcement.violations (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_evidence_content_size
        CHECK (file_size > 0 AND file_size = octet_length(content)),

    CONSTRAINT ck_evidence_content_sha256
        CHECK (sha256_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT ck_evidence_content_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),

    CONSTRAINT ck_evidence_content_extension_type
        CHECK (
            (storage_key LIKE '%.jpg' AND content_type = 'image/jpeg') OR
            (storage_key LIKE '%.png' AND content_type = 'image/png') OR
            (storage_key LIKE '%.pdf' AND content_type = 'application/pdf')
        ),

    CONSTRAINT ck_evidence_content_key
        CHECK (storage_key ~ ('^violations/' || violation_id::text || '/[0-9a-f]{64}\.(jpg|png|pdf)$'))
);

-- Existing evidence.id is unique, so this additive key does not change or remove rows.
ALTER TABLE enforcement.evidence
    ADD CONSTRAINT uq_evidence_id_violation_storage_key
    UNIQUE (id, violation_id, storage_key);

ALTER TABLE enforcement.evidence_content
    ADD CONSTRAINT fk_evidence_content_metadata_identity
    FOREIGN KEY (evidence_id, violation_id, storage_key)
    REFERENCES enforcement.evidence (id, violation_id, storage_key)
    ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_evidence_content_violation_id
    ON enforcement.evidence_content (violation_id);
