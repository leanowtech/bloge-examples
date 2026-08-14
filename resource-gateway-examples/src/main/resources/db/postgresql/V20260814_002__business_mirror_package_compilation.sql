-- Resource Gateway Business Mirror Package compilation v1.
-- Apply after V20260814_001 and before enabling businessMirrorPackageCompilerApi.

CREATE TABLE IF NOT EXISTS business_mirror_package_compilation_locks (
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

CREATE TABLE IF NOT EXISTS business_mirror_package_compilation_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    next_revision BIGINT NOT NULL CHECK (next_revision > 0),
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id
    )
);

CREATE TABLE IF NOT EXISTS business_mirror_package_compilations (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    request_fingerprint VARCHAR(80) NOT NULL,
    source_draft_revision BIGINT NOT NULL CHECK (source_draft_revision > 0),
    source_draft_fingerprint VARCHAR(80) NOT NULL,
    readiness_status VARCHAR(32) NOT NULL CHECK (
        readiness_status IN ('READY', 'REVIEW_REQUIRED', 'BLOCKED')
    ),
    authority_generation VARCHAR(512) NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, compilation_revision
    ),
    CHECK (request_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (source_draft_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_package_readiness_reports (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    fact_fingerprint VARCHAR(80) NOT NULL,
    fact_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, compilation_revision
    ),
    CHECK (fact_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_package_asset_link_closures (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    fact_fingerprint VARCHAR(80) NOT NULL,
    fact_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, compilation_revision
    ),
    CHECK (fact_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_package_snapshots (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    fact_fingerprint VARCHAR(80) NOT NULL,
    fact_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, compilation_revision
    ),
    CHECK (fact_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_package_compile_locks (
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

CREATE TABLE IF NOT EXISTS business_mirror_package_compile_receipts (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(80) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    receipt_json TEXT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key
    ),
    CHECK (request_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_package_compilation_source_idx
    ON business_mirror_package_compilations (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, source_draft_revision, compilation_revision DESC
    );

CREATE INDEX IF NOT EXISTS business_mirror_package_compile_receipt_completed_idx
    ON business_mirror_package_compile_receipts (
        tenant_id, organization_id, project_id, environment_id, region_id, completed_at
    );
