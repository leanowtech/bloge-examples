-- Resource Gateway Business Mirror reverse-impact projection v1.
-- The immutable Package Snapshot and Link Closure remain authoritative; these tables are rebuildable.

CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_locks (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id
    )
);

CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    closure_id VARCHAR(512) NOT NULL,
    closure_revision BIGINT NOT NULL CHECK (closure_revision > 0),
    closure_fingerprint VARCHAR(80) NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id
    ),
    CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (closure_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_projections (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    source_layer VARCHAR(64) NOT NULL CHECK (source_layer IN (
        'L0_RESOURCE', 'L1_SERVICE_DESIGN', 'L2_SERVICE_CARRIER', 'L3_APPLICATION'
    )),
    source_kind VARCHAR(64) NOT NULL CHECK (source_kind IN (
        'RESOURCE', 'OPERATOR', 'BUILT_IN_FUNCTION', 'FEATURE', 'SCENARIO', 'SOLUTION',
        'SOP', 'AGENT', 'WORKFLOW', 'CHANNEL_APPLICATION'
    )),
    source_id VARCHAR(512) NOT NULL,
    source_authority VARCHAR(512) NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision > 0),
    source_fingerprint VARCHAR(80) NOT NULL,
    source_ref_json TEXT NOT NULL,
    paths_json TEXT NOT NULL,
    projection_fingerprint VARCHAR(80) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, compilation_revision, source_layer, source_kind, source_id,
        source_authority, source_revision, source_fingerprint
    ),
    CHECK (source_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (projection_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_asset_impact_source_idx
    ON business_mirror_asset_impact_projections (
        tenant_id, organization_id, project_id, environment_id, region_id,
        source_kind, source_id, source_authority, package_id, compilation_revision
    );

CREATE INDEX IF NOT EXISTS business_mirror_asset_impact_projected_at_idx
    ON business_mirror_asset_impact_heads (
        tenant_id, organization_id, project_id, environment_id, region_id, projected_at
    );

-- Transactional projection outbox. Projection failures cannot roll back authoritative Snapshots.
CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_outbox (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'PENDING', 'PROJECTING', 'COMPLETED', 'QUARANTINED'
    )),
    lease_owner VARCHAR(512) NOT NULL,
    lease_epoch BIGINT NOT NULL CHECK (lease_epoch >= 0),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    last_failure_code VARCHAR(256) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, compilation_revision
    ),
    CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (
        status = 'PROJECTING' AND lease_owner <> '' AND lease_expires_at IS NOT NULL
        OR status <> 'PROJECTING' AND lease_owner = '' AND lease_expires_at IS NULL
    )
);

CREATE INDEX IF NOT EXISTS business_mirror_asset_impact_outbox_ready_idx
    ON business_mirror_asset_impact_outbox (status, available_at, created_at);
