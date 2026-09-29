CREATE TABLE configuration.configurations (
    id UUID PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL,
    config_value TEXT NOT NULL,
    description VARCHAR(500),
    data_type VARCHAR(30) NOT NULL DEFAULT 'STRING',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_configurations_key
        UNIQUE (config_key),

    CONSTRAINT ck_configurations_data_type
        CHECK (data_type IN (
            'STRING',
            'INTEGER',
            'DECIMAL',
            'BOOLEAN',
            'JSON'
        ))
);

CREATE INDEX idx_configurations_active
    ON configuration.configurations (active);

CREATE INDEX idx_configurations_data_type
    ON configuration.configurations (data_type);
