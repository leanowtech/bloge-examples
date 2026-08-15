-- Protected Correctness Fixture material vault v2.
-- This migration is intentionally separate from metadata-only authoring tables.
-- Plaintext payloads are never persisted; protected_payload contains an AES-GCM envelope.

CREATE TABLE IF NOT EXISTS rg_fixture_material_v2_revisions (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    fixture_asset_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    material_fingerprint VARCHAR(80) NOT NULL
        CHECK (material_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    classification VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN ('AVAILABLE', 'EXPIRED')),
    receipt_json JSONB NOT NULL,
    protected_payload TEXT,
    record_fingerprint VARCHAR(80) NOT NULL
        CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        fixture_asset_id, revision
    ),
    CHECK (
        (state = 'AVAILABLE' AND protected_payload IS NOT NULL)
        OR (state = 'EXPIRED' AND protected_payload IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS rg_fixture_material_v2_expiry_idx
    ON rg_fixture_material_v2_revisions (
        state, expires_at, tenant_id, organization_id, project_id, environment_id, region_id
    );

CREATE TABLE IF NOT EXISTS rg_fixture_material_access_audit (
    access_id VARCHAR(512) PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    material_id VARCHAR(512) NOT NULL,
    material_revision BIGINT NOT NULL CHECK (material_revision > 0),
    material_fingerprint VARCHAR(80) NOT NULL
        CHECK (material_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    actor_id VARCHAR(512) NOT NULL,
    purpose VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL CHECK (action IN ('WRITE', 'READ')),
    outcome VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS rg_fixture_material_audit_coordinate_idx
    ON rg_fixture_material_access_audit (
        tenant_id, organization_id, project_id, environment_id, region_id,
        material_id, material_revision, occurred_at
    );
