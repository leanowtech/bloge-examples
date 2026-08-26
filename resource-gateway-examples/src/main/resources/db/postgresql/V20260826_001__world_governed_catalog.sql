-- Stage 1-D generic governed catalog for logical contracts, worlds, and scenarios.
-- canonical_json is TEXT so the same migration is executable by PostgreSQL and H2.

CREATE TABLE IF NOT EXISTS rg_world_catalog_heads (
    tenant_id VARCHAR(255) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    asset_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    record_fingerprint VARCHAR(80) NOT NULL CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO')),
    canonical_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, kind, asset_id)
);

CREATE TABLE IF NOT EXISTS rg_world_catalog_revisions (
    tenant_id VARCHAR(255) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    asset_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    record_fingerprint VARCHAR(80) NOT NULL CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO')),
    canonical_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, kind, asset_id, revision)
);
