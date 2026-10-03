DELETE FROM enforcement.evidence e
WHERE e.id IN (
    SELECT id
    FROM (
        SELECT
            id,
            ROW_NUMBER() OVER (
                PARTITION BY violation_id, storage_key, sha256_hash
                ORDER BY created_at ASC, id ASC
            ) AS rn
        FROM enforcement.evidence
    ) duplicates
    WHERE rn > 1
);

ALTER TABLE enforcement.evidence
    ADD CONSTRAINT uk_evidence_violation_storage_hash
    UNIQUE (violation_id, storage_key, sha256_hash);