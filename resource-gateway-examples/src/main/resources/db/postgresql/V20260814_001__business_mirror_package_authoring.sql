-- Resource Gateway Business Mirror Package authoring v1.
-- Apply with the enterprise migration runner before enabling businessMirrorPackageApi.

CREATE TABLE IF NOT EXISTS business_mirror_package_drafts (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    stored_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
);

CREATE TABLE IF NOT EXISTS business_mirror_package_draft_revisions (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    stored_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id, revision
    )
);

CREATE TABLE IF NOT EXISTS business_mirror_package_save_locks (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key
    )
);

CREATE TABLE IF NOT EXISTS business_mirror_package_save_receipts (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(80) NOT NULL,
    receipt_json TEXT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key
    ),
    CHECK (request_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_package_draft_history_lookup_idx
    ON business_mirror_package_draft_revisions (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id, revision DESC
    );

CREATE INDEX IF NOT EXISTS business_mirror_package_receipt_completed_idx
    ON business_mirror_package_save_receipts (
        tenant_id, organization_id, project_id, environment_id, region_id, completed_at
    );
