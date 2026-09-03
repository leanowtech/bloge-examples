CREATE TABLE agent_tdd_assets (
    scope_key VARCHAR(1024) NOT NULL,
    asset_kind VARCHAR(64) NOT NULL,
    asset_ref VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(255) NOT NULL,
    state_json TEXT NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (scope_key, asset_kind, asset_ref)
);

CREATE TABLE agent_tdd_idempotency (
    scope_key VARCHAR(1024) NOT NULL,
    operation VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(255) NOT NULL,
    response_json TEXT NOT NULL,
    completed BOOLEAN DEFAULT TRUE NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (scope_key, operation, idempotency_key)
);
