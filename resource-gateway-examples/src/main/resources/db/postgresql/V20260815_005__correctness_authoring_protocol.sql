-- Resource Gateway Correctness Studio canonical authoring storage v1.
-- Apply with the enterprise migration runner before enabling correctnessAuthoringApi.
-- Payload-bearing Fixture material remains in the protected material store and never enters these tables.

CREATE TABLE IF NOT EXISTS rg_correctness_definition_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    definition_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision > 0),
    target_fingerprint VARCHAR(80) NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    lifecycle VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, definition_id)
);

CREATE TABLE IF NOT EXISTS rg_correctness_definition_revisions (
    LIKE rg_correctness_definition_heads INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE rg_correctness_definition_revisions
    DROP CONSTRAINT IF EXISTS rg_correctness_definition_revisions_pkey;
ALTER TABLE rg_correctness_definition_revisions
    ADD PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, definition_id, revision
    );

CREATE UNIQUE INDEX IF NOT EXISTS rg_correctness_definition_revision_fingerprint_uidx
    ON rg_correctness_definition_revisions (
        tenant_id, organization_id, project_id, environment_id, region_id,
        definition_id, revision, fingerprint
    );

CREATE INDEX IF NOT EXISTS rg_correctness_definition_target_idx
    ON rg_correctness_definition_heads (
        tenant_id, organization_id, project_id, environment_id, region_id,
        target_kind, target_id, target_fingerprint
    );

CREATE TABLE IF NOT EXISTS rg_coverage_inventory_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    inventory_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision > 0),
    target_fingerprint VARCHAR(80) NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    lifecycle VARCHAR(32) NOT NULL,
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, inventory_id)
);

CREATE TABLE IF NOT EXISTS rg_coverage_inventory_revisions (
    LIKE rg_coverage_inventory_heads INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE rg_coverage_inventory_revisions
    DROP CONSTRAINT IF EXISTS rg_coverage_inventory_revisions_pkey;
ALTER TABLE rg_coverage_inventory_revisions
    ADD PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, inventory_id, revision
    );

CREATE TABLE IF NOT EXISTS rg_coverage_obligation_index (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    inventory_id VARCHAR(512) NOT NULL,
    inventory_revision BIGINT NOT NULL CHECK (inventory_revision > 0),
    obligation_id VARCHAR(512) NOT NULL,
    obligation_fingerprint VARCHAR(80) NOT NULL CHECK (obligation_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    dimension VARCHAR(32) NOT NULL,
    risk VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        inventory_id, inventory_revision, obligation_id
    )
);

CREATE INDEX IF NOT EXISTS rg_coverage_obligation_filter_idx
    ON rg_coverage_obligation_index (
        tenant_id, organization_id, project_id, environment_id, region_id,
        dimension, risk, owner_id, lifecycle, source
    );

CREATE TABLE IF NOT EXISTS rg_business_oracle_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    oracle_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    basis_fingerprint VARCHAR(80) NOT NULL CHECK (basis_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision > 0),
    target_fingerprint VARCHAR(80) NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    lifecycle VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, oracle_id)
);

CREATE TABLE IF NOT EXISTS rg_business_oracle_revisions (
    LIKE rg_business_oracle_heads INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE rg_business_oracle_revisions
    DROP CONSTRAINT IF EXISTS rg_business_oracle_revisions_pkey;
ALTER TABLE rg_business_oracle_revisions
    ADD PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, oracle_id, revision
    );

CREATE INDEX IF NOT EXISTS rg_business_oracle_target_owner_idx
    ON rg_business_oracle_heads (
        tenant_id, organization_id, project_id, environment_id, region_id,
        target_kind, target_id, target_fingerprint, owner_id, lifecycle
    );

CREATE TABLE IF NOT EXISTS rg_assertion_set_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    assertion_set_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision > 0),
    target_fingerprint VARCHAR(80) NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    oracle_id VARCHAR(512) NOT NULL,
    oracle_revision BIGINT NOT NULL CHECK (oracle_revision > 0),
    oracle_fingerprint VARCHAR(80) NOT NULL CHECK (oracle_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    lifecycle VARCHAR(32) NOT NULL,
    compatibility_supported BOOLEAN NOT NULL,
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, assertion_set_id
    )
);

CREATE TABLE IF NOT EXISTS rg_assertion_set_revisions (
    LIKE rg_assertion_set_heads INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE rg_assertion_set_revisions
    DROP CONSTRAINT IF EXISTS rg_assertion_set_revisions_pkey;
ALTER TABLE rg_assertion_set_revisions
    ADD PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        assertion_set_id, revision
    );

CREATE INDEX IF NOT EXISTS rg_assertion_set_oracle_idx
    ON rg_assertion_set_heads (
        tenant_id, organization_id, project_id, environment_id, region_id,
        oracle_id, oracle_revision, oracle_fingerprint, lifecycle, compatibility_supported
    );

CREATE TABLE IF NOT EXISTS rg_scenario_draft_set_v2_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    scenario_draft_set_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    target_revision BIGINT NOT NULL CHECK (target_revision > 0),
    target_fingerprint VARCHAR(80) NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    contract_kind VARCHAR(64) NOT NULL,
    contract_id VARCHAR(512) NOT NULL,
    contract_revision BIGINT NOT NULL CHECK (contract_revision > 0),
    contract_fingerprint VARCHAR(80) NOT NULL CHECK (contract_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, scenario_draft_set_id
    )
);

CREATE TABLE IF NOT EXISTS rg_scenario_draft_set_v2_revisions (
    LIKE rg_scenario_draft_set_v2_heads INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE rg_scenario_draft_set_v2_revisions
    DROP CONSTRAINT IF EXISTS rg_scenario_draft_set_v2_revisions_pkey;
ALTER TABLE rg_scenario_draft_set_v2_revisions
    ADD PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        scenario_draft_set_id, revision
    );

CREATE TABLE IF NOT EXISTS rg_scenario_case_v2_index (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    scenario_draft_set_id VARCHAR(512) NOT NULL,
    scenario_draft_set_revision BIGINT NOT NULL CHECK (scenario_draft_set_revision > 0),
    case_id VARCHAR(512) NOT NULL,
    case_fingerprint VARCHAR(80) NOT NULL CHECK (case_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    lifecycle VARCHAR(32) NOT NULL,
    risk VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    owner_kind VARCHAR(32) NOT NULL,
    case_type VARCHAR(32) NOT NULL,
    case_name VARCHAR(1024) NOT NULL,
    business_intent VARCHAR(16384) NOT NULL,
    obligation_count INTEGER NOT NULL CHECK (obligation_count >= 0),
    oracle_count INTEGER NOT NULL CHECK (oracle_count >= 0),
    assertion_set_count INTEGER NOT NULL CHECK (assertion_set_count >= 0),
    dependency_count INTEGER NOT NULL CHECK (dependency_count >= 0),
    review_status VARCHAR(32) NOT NULL,
    tags_json JSONB NOT NULL,
    canonical_json JSONB NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        scenario_draft_set_id, scenario_draft_set_revision, case_id
    )
);

CREATE INDEX IF NOT EXISTS rg_scenario_case_v2_filter_idx
    ON rg_scenario_case_v2_index (
        tenant_id, organization_id, project_id, environment_id, region_id,
        lifecycle, risk, owner_id, case_type, case_name, case_id
    );

CREATE TABLE IF NOT EXISTS rg_scenario_case_obligation_ref_index (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    scenario_draft_set_id VARCHAR(512) NOT NULL,
    scenario_draft_set_revision BIGINT NOT NULL CHECK (scenario_draft_set_revision > 0),
    case_id VARCHAR(512) NOT NULL,
    case_lifecycle VARCHAR(32) NOT NULL,
    inventory_id VARCHAR(512) NOT NULL,
    inventory_revision BIGINT NOT NULL CHECK (inventory_revision > 0),
    inventory_fingerprint VARCHAR(80) NOT NULL CHECK (inventory_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    obligation_id VARCHAR(512) NOT NULL,
    obligation_fingerprint VARCHAR(80) NOT NULL CHECK (obligation_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        scenario_draft_set_id, scenario_draft_set_revision, case_id,
        inventory_id, inventory_revision, obligation_id
    )
);

CREATE INDEX IF NOT EXISTS rg_scenario_case_obligation_lookup_idx
    ON rg_scenario_case_obligation_ref_index (
        tenant_id, organization_id, project_id, environment_id, region_id,
        inventory_id, inventory_revision, inventory_fingerprint, obligation_id,
        case_lifecycle
    );

CREATE TABLE IF NOT EXISTS rg_fixture_asset_heads (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    fixture_asset_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    schema_id VARCHAR(512) NOT NULL,
    schema_revision BIGINT NOT NULL CHECK (schema_revision > 0),
    schema_fingerprint VARCHAR(80) NOT NULL CHECK (schema_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    variant_key VARCHAR(512) NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    material_id VARCHAR(512) NOT NULL,
    material_revision BIGINT NOT NULL CHECK (material_revision > 0),
    material_fingerprint VARCHAR(80) NOT NULL CHECK (material_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, fixture_asset_id
    )
);

CREATE TABLE IF NOT EXISTS rg_fixture_asset_revisions (
    LIKE rg_fixture_asset_heads INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE rg_fixture_asset_revisions
    DROP CONSTRAINT IF EXISTS rg_fixture_asset_revisions_pkey;
ALTER TABLE rg_fixture_asset_revisions
    ADD PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        fixture_asset_id, revision
    );

CREATE INDEX IF NOT EXISTS rg_fixture_asset_catalog_idx
    ON rg_fixture_asset_heads (
        tenant_id, organization_id, project_id, environment_id, region_id,
        schema_id, schema_fingerprint, variant_key, lifecycle, classification, owner_id
    );

CREATE TABLE IF NOT EXISTS rg_fixture_usage_index (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    fixture_asset_id VARCHAR(512) NOT NULL,
    fixture_revision BIGINT NOT NULL CHECK (fixture_revision > 0),
    fixture_fingerprint VARCHAR(80) NOT NULL CHECK (fixture_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    consumer_kind VARCHAR(64) NOT NULL,
    consumer_id VARCHAR(512) NOT NULL,
    consumer_revision BIGINT NOT NULL CHECK (consumer_revision > 0),
    consumer_fingerprint VARCHAR(80) NOT NULL CHECK (consumer_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        fixture_asset_id, fixture_revision, consumer_kind, consumer_id, consumer_revision
    )
);

CREATE INDEX IF NOT EXISTS rg_fixture_usage_reverse_idx
    ON rg_fixture_usage_index (
        tenant_id, organization_id, project_id, environment_id, region_id,
        consumer_kind, consumer_id, consumer_revision
    );

CREATE TABLE IF NOT EXISTS rg_correctness_publications (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    publication_id VARCHAR(512) NOT NULL,
    publication_fingerprint VARCHAR(80) NOT NULL CHECK (publication_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    definition_id VARCHAR(512) NOT NULL,
    definition_revision BIGINT NOT NULL CHECK (definition_revision > 0),
    definition_fingerprint VARCHAR(80) NOT NULL CHECK (definition_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    inventory_id VARCHAR(512) NOT NULL,
    inventory_revision BIGINT NOT NULL CHECK (inventory_revision > 0),
    inventory_fingerprint VARCHAR(80) NOT NULL CHECK (inventory_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    scenario_draft_set_id VARCHAR(512) NOT NULL,
    scenario_draft_set_revision BIGINT NOT NULL CHECK (scenario_draft_set_revision > 0),
    scenario_draft_set_fingerprint VARCHAR(80) NOT NULL CHECK (scenario_draft_set_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    canonical_json JSONB NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    committed_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, publication_id
    ),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region_id,
        publication_fingerprint
    )
);

CREATE TABLE IF NOT EXISTS rg_correctness_publication_attempts (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(512) NOT NULL,
    state_version BIGINT NOT NULL CHECK (state_version > 0),
    idempotency_key_fingerprint VARCHAR(80) NOT NULL CHECK (idempotency_key_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    stage VARCHAR(32) NOT NULL,
    canonical_json JSONB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, attempt_id),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region_id,
        idempotency_key_fingerprint
    )
);

CREATE TABLE IF NOT EXISTS rg_correctness_publication_attempt_history (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(512) NOT NULL,
    state_version BIGINT NOT NULL CHECK (state_version > 0),
    stage VARCHAR(32) NOT NULL,
    canonical_json JSONB NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        attempt_id, state_version
    )
);

CREATE TABLE IF NOT EXISTS rg_correctness_command_receipts (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    command_kind VARCHAR(64) NOT NULL,
    idempotency_key_fingerprint VARCHAR(80) NOT NULL
        CHECK (idempotency_key_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    request_fingerprint VARCHAR(80) NOT NULL
        CHECK (request_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    result_kind VARCHAR(64) NOT NULL,
    result_id VARCHAR(512) NOT NULL,
    result_revision BIGINT NOT NULL CHECK (result_revision > 0),
    result_fingerprint VARCHAR(80) NOT NULL
        CHECK (result_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    actor_id VARCHAR(512) NOT NULL,
    receipt_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id,
        command_kind, idempotency_key_fingerprint
    )
);

CREATE TABLE IF NOT EXISTS rg_correctness_outbox (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(512) NOT NULL,
    aggregate_kind VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(512) NOT NULL,
    aggregate_revision BIGINT NOT NULL CHECK (aggregate_revision > 0),
    event_type VARCHAR(128) NOT NULL,
    event_json JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, event_id)
);

CREATE INDEX IF NOT EXISTS rg_correctness_outbox_pending_idx
    ON rg_correctness_outbox (
        tenant_id, organization_id, project_id, environment_id, region_id,
        published_at, occurred_at, aggregate_kind, aggregate_id, aggregate_revision
    );
