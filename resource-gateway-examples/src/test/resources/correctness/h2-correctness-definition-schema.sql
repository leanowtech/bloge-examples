CREATE TABLE rg_correctness_definition_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    definition_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL,
    fingerprint VARCHAR(80) NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL,
    target_fingerprint VARCHAR(80) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    canonical_json CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, definition_id)
);

CREATE TABLE rg_correctness_definition_revisions (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    definition_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL,
    fingerprint VARCHAR(80) NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL,
    target_fingerprint VARCHAR(80) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    canonical_json CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, definition_id, revision
    )
);

CREATE TABLE rg_correctness_outbox (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(512) NOT NULL,
    aggregate_kind VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(512) NOT NULL,
    aggregate_revision BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_json CLOB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, event_id)
);
