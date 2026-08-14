-- ANEKE Package governance projection v1.
-- Resource Gateway stores only signed external projections and a monotonic current head.

CREATE TABLE IF NOT EXISTS business_mirror_package_governance_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    projection_id VARCHAR(512) NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    external_generation BIGINT NOT NULL CHECK (external_generation >= 0),
    projection_fingerprint VARCHAR(80) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id
    ),
    CHECK (
        external_generation = 0 AND projection_fingerprint = ''
        OR external_generation > 0
           AND projection_fingerprint ~ '^sha256:[a-f0-9]{64}$'
    )
);

CREATE TABLE IF NOT EXISTS business_mirror_package_governance_projections (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    external_generation BIGINT NOT NULL CHECK (external_generation > 0),
    projection_id VARCHAR(512) NOT NULL,
    projection_revision BIGINT NOT NULL CHECK (projection_revision > 0),
    projection_fingerprint VARCHAR(80) NOT NULL,
    package_snapshot_fingerprint VARCHAR(80) NOT NULL,
    registry_bundle_fingerprint VARCHAR(80) NOT NULL,
    evidence_index_fingerprint VARCHAR(80) NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    projection_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, external_generation
    ),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region_id,
        projection_fingerprint
    ),
    CHECK (projection_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (package_snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (registry_bundle_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (evidence_index_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_package_governance_expiry_idx
    ON business_mirror_package_governance_projections (
        tenant_id, organization_id, project_id, environment_id, region_id, expires_at
    );
