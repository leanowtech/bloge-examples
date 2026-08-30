-- Slice 2A-2a API Resource authoring persistence.
-- Keep this migration executable by PostgreSQL and H2 MODE=PostgreSQL:
-- JSON documents are TEXT and opaque identifiers are VARCHAR values.

CREATE TABLE IF NOT EXISTS rg_authoring_command_journal (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    endpoint VARCHAR(128) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    expected_mode VARCHAR(16) NOT NULL,
    expected_revision BIGINT NULL,
    receipt_schema VARCHAR(128) NULL,
    receipt_json TEXT NULL,
    receipt_fingerprint VARCHAR(128) NULL,
    receipt_etag VARCHAR(256) NULL,
    failure_code VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_authoring_command_journal_pk PRIMARY KEY (command_id),
    CONSTRAINT rg_authoring_command_journal_coordinate_uq UNIQUE
        (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key),
    CONSTRAINT rg_authoring_command_journal_status_ck CHECK (status IN ('PREPARING', 'COMMITTED', 'FAILED')),
    CONSTRAINT rg_authoring_command_journal_attempt_ck CHECK (attempt_no > 0),
    CONSTRAINT rg_authoring_command_journal_expected_ck CHECK
        ((expected_mode = 'CREATE' AND expected_revision IS NULL)
         OR (expected_mode = 'MATCH' AND expected_revision > 0)),
    CONSTRAINT rg_authoring_command_journal_receipt_ck CHECK
        ((status = 'PREPARING' AND receipt_schema IS NULL AND receipt_json IS NULL
             AND receipt_fingerprint IS NULL AND receipt_etag IS NULL AND failure_code IS NULL)
         OR (status = 'COMMITTED' AND receipt_schema IS NOT NULL AND receipt_json IS NOT NULL
             AND receipt_fingerprint IS NOT NULL AND receipt_etag IS NOT NULL AND failure_code IS NULL)
         OR (status = 'FAILED' AND receipt_schema IS NULL AND receipt_json IS NULL
             AND receipt_fingerprint IS NULL AND receipt_etag IS NULL AND failure_code IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS rg_authoring_command_journal_lease_recovery_idx
    ON rg_authoring_command_journal (status, lease_until);

CREATE TABLE IF NOT EXISTS rg_api_resource_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    spec_json TEXT NOT NULL,
    spec_fingerprint VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_resource_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id, revision),
    CONSTRAINT rg_api_resource_revisions_command_uq UNIQUE (command_id),
    CONSTRAINT rg_api_resource_revisions_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, resource_id, revision, strong_etag),
    CONSTRAINT rg_api_resource_revisions_command_fk FOREIGN KEY (command_id)
        REFERENCES rg_authoring_command_journal (command_id),
    CONSTRAINT rg_api_resource_revisions_state_ck CHECK (state IN ('STAGED', 'COMMITTED')),
    CONSTRAINT rg_api_resource_revisions_revision_ck CHECK (revision > 0),
    CONSTRAINT rg_api_resource_revisions_attempt_ck CHECK (attempt_no > 0)
);

CREATE INDEX IF NOT EXISTS rg_api_resource_revisions_connection_visibility_idx
    ON rg_api_resource_revisions
       (tenant_id, project_id, environment_id, resource_id, connection_id, state);
CREATE INDEX IF NOT EXISTS rg_api_resource_revisions_staging_cleanup_idx
    ON rg_api_resource_revisions (state, updated_at);

CREATE TABLE IF NOT EXISTS rg_api_resource_projection_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    descriptor_json TEXT NOT NULL,
    descriptor_fingerprint VARCHAR(128) NOT NULL,
    descriptor_state VARCHAR(16) NOT NULL,
    design_contract_json TEXT NOT NULL,
    design_contract_fingerprint VARCHAR(128) NOT NULL,
    design_contract_state VARCHAR(16) NOT NULL,
    operator_json TEXT NOT NULL,
    operator_fingerprint VARCHAR(128) NOT NULL,
    operator_state VARCHAR(16) NOT NULL,
    set_fingerprint VARCHAR(128) NOT NULL,
    CONSTRAINT rg_api_resource_projection_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id, revision),
    CONSTRAINT rg_api_resource_projection_revisions_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision)
        REFERENCES rg_api_resource_revisions
            (tenant_id, project_id, environment_id, resource_id, revision)
        ON DELETE CASCADE,
    CONSTRAINT rg_api_resource_projection_revisions_state_ck CHECK
        (descriptor_state = 'READY' AND design_contract_state = 'READY' AND operator_state = 'READY')
);

CREATE TABLE IF NOT EXISTS rg_api_resource_heads (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_resource_heads_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id),
    CONSTRAINT rg_api_resource_heads_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision, strong_etag)
        REFERENCES rg_api_resource_revisions
            (tenant_id, project_id, environment_id, resource_id, revision, strong_etag)
);
