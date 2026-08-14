-- Durable single-use authorization and epoch-fenced runtime-certification journal.
-- Apply before installing a RuntimeCertificationEnvironmentAdapter.

CREATE TABLE IF NOT EXISTS mirror_runtime_certification_authorization_locks (
    authorization_nonce_fingerprint VARCHAR(71) NOT NULL,
    PRIMARY KEY (authorization_nonce_fingerprint),
    CHECK (authorization_nonce_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS mirror_runtime_certification_runs (
    run_id VARCHAR(512) NOT NULL,
    manifest_fingerprint VARCHAR(71) NOT NULL,
    authorization_fingerprint VARCHAR(71) NOT NULL,
    authorization_nonce_fingerprint VARCHAR(71) NOT NULL,
    environment_fingerprint VARCHAR(71) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED')),
    owner_id VARCHAR(512) NOT NULL,
    lease_epoch BIGINT NOT NULL CHECK (lease_epoch > 0),
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    scenario_count INTEGER NOT NULL CHECK (scenario_count BETWEEN 0 AND 12),
    state_fingerprint VARCHAR(71) NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE (authorization_fingerprint),
    UNIQUE (authorization_nonce_fingerprint),
    CHECK (manifest_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (authorization_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (authorization_nonce_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (environment_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (state_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (updated_at >= created_at),
    CHECK ((status = 'COMPLETED') = (owner_id = '')),
    CHECK (status <> 'COMPLETED' OR lease_expires_at = TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00')
);

CREATE INDEX IF NOT EXISTS idx_mirror_runtime_certification_status
    ON mirror_runtime_certification_runs (status, lease_expires_at, run_id);
