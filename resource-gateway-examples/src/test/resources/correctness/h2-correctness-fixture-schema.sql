CREATE TABLE rg_fixture_asset_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    fixture_asset_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL,
    fingerprint VARCHAR(80) NOT NULL,
    schema_id VARCHAR(512) NOT NULL,
    schema_revision BIGINT NOT NULL,
    schema_fingerprint VARCHAR(80) NOT NULL,
    variant_key VARCHAR(512) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    material_id VARCHAR(512) NOT NULL,
    material_revision BIGINT NOT NULL,
    material_fingerprint VARCHAR(80) NOT NULL,
    canonical_json CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, fixture_asset_id
    )
);

CREATE TABLE rg_fixture_asset_revisions (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    fixture_asset_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL,
    fingerprint VARCHAR(80) NOT NULL,
    schema_id VARCHAR(512) NOT NULL,
    schema_revision BIGINT NOT NULL,
    schema_fingerprint VARCHAR(80) NOT NULL,
    variant_key VARCHAR(512) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    material_id VARCHAR(512) NOT NULL,
    material_revision BIGINT NOT NULL,
    material_fingerprint VARCHAR(80) NOT NULL,
    canonical_json CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        fixture_asset_id, revision
    )
);

CREATE TABLE rg_fixture_usage_index (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    fixture_asset_id VARCHAR(512) NOT NULL,
    fixture_revision BIGINT NOT NULL,
    fixture_fingerprint VARCHAR(80) NOT NULL,
    consumer_kind VARCHAR(64) NOT NULL,
    consumer_id VARCHAR(512) NOT NULL,
    consumer_revision BIGINT NOT NULL,
    consumer_fingerprint VARCHAR(80) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        fixture_asset_id, fixture_revision, consumer_kind, consumer_id, consumer_revision
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
