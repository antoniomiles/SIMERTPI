ALTER TABLE enforcement.violations
    ADD CONSTRAINT uk_violations_inspection_id
    UNIQUE (inspection_id);
