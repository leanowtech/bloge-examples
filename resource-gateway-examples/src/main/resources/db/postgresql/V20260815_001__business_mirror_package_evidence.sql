-- Business Mirror Package Evidence/Fidelity v1.
-- Apply after Package compilation and before enabling Package Evidence projection workers.

CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_locks (
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

CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_sequences (
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

CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_indexes (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    projection_revision BIGINT NOT NULL CHECK (projection_revision > 0),
    index_fingerprint VARCHAR(80) NOT NULL,
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    domain_id VARCHAR(512) NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    index_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        package_id, projection_revision
    ),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region_id,
        index_fingerprint
    ),
    CHECK (index_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    projection_revision BIGINT NOT NULL CHECK (projection_revision > 0),
    index_fingerprint VARCHAR(80) NOT NULL,
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    domain_id VARCHAR(512) NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, package_id
    ),
    CHECK (index_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_package_evidence_domain_idx
    ON business_mirror_package_evidence_heads (
        tenant_id, organization_id, project_id, environment_id, region_id,
        domain_id, package_id
    );

CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_outbox (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('PENDING', 'PROJECTING', 'COMPLETED', 'QUARANTINED')
    ),
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
    CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_package_evidence_outbox_ready_idx
    ON business_mirror_package_evidence_outbox (status, available_at, created_at);

CREATE TABLE IF NOT EXISTS business_mirror_evidence_owner_tasks (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(1024) NOT NULL,
    task_version BIGINT NOT NULL CHECK (task_version > 0),
    task_fingerprint VARCHAR(80) NOT NULL,
    package_id VARCHAR(512) NOT NULL,
    compilation_revision BIGINT NOT NULL CHECK (compilation_revision > 0),
    projection_revision BIGINT NOT NULL CHECK (projection_revision > 0),
    domain_id VARCHAR(512) NOT NULL,
    reason VARCHAR(96) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'SUPERSEDED')
    ),
    owner_id VARCHAR(1024) NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    task_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, task_id
    ),
    CHECK (task_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS business_mirror_evidence_owner_task_events (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(1024) NOT NULL,
    task_version BIGINT NOT NULL CHECK (task_version > 0),
    task_fingerprint VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'SUPERSEDED')
    ),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    task_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        task_id, task_version
    ),
    CHECK (task_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE INDEX IF NOT EXISTS business_mirror_evidence_owner_task_domain_idx
    ON business_mirror_evidence_owner_tasks (
        tenant_id, organization_id, project_id, environment_id, region_id,
        domain_id, package_id, status, due_at
    );
