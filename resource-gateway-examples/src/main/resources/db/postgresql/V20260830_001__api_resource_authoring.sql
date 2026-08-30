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
    request_fingerprint VARCHAR(128) NOT NULL
        CHECK (CHAR_LENGTH(request_fingerprint) = 71 AND request_fingerprint LIKE 'sha256:%'),
    status VARCHAR(32) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    expected_mode VARCHAR(16) NOT NULL,
    expected_revision BIGINT NULL,
    receipt_schema VARCHAR(128) NULL,
    receipt_json TEXT NULL,
    receipt_fingerprint VARCHAR(128) NULL,
    receipt_etag VARCHAR(256) NULL,
    failure_code VARCHAR(128) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_authoring_command_journal_pk PRIMARY KEY (command_id),
    CONSTRAINT rg_authoring_command_journal_coordinate_uq UNIQUE
        (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key),
    CONSTRAINT rg_authoring_command_journal_attempt_uq UNIQUE
        (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_authoring_command_journal_status_ck CHECK (status IN ('PREPARING', 'COMMITTED', 'FAILED')),
    CONSTRAINT rg_authoring_command_journal_attempt_ck CHECK (attempt_no > 0),
    CONSTRAINT rg_authoring_command_journal_expected_ck CHECK
        ((expected_mode = 'CREATE' AND expected_revision IS NULL)
         OR (expected_mode = 'MATCH' AND expected_revision > 0)),
    CONSTRAINT rg_authoring_command_journal_receipt_fp_ck CHECK
        (receipt_fingerprint IS NULL OR
            (CHAR_LENGTH(receipt_fingerprint) = 71 AND receipt_fingerprint LIKE 'sha256:%')),
    CONSTRAINT rg_authoring_command_journal_receipt_etag_ck CHECK
        (receipt_etag IS NULL OR
            (CHAR_LENGTH(receipt_etag) >= 3 AND receipt_etag LIKE '"%"' AND receipt_etag NOT LIKE '"W/%')),
    CONSTRAINT rg_authoring_command_journal_failure_ck CHECK
        (failure_code IS NULL OR
            (CHAR_LENGTH(failure_code) BETWEEN 1 AND 128
             AND failure_code = UPPER(failure_code)
             AND failure_code NOT LIKE '% %')),
    CONSTRAINT rg_authoring_command_journal_receipt_ck CHECK
        ((status = 'PREPARING' AND receipt_schema IS NULL AND receipt_json IS NULL
             AND receipt_fingerprint IS NULL AND receipt_etag IS NULL AND failure_code IS NULL)
         OR (status = 'COMMITTED' AND receipt_schema IS NOT NULL AND receipt_json IS NOT NULL
             AND receipt_fingerprint IS NOT NULL AND receipt_etag IS NOT NULL AND failure_code IS NULL)
         OR (status = 'FAILED' AND receipt_schema IS NULL AND receipt_json IS NULL
             AND receipt_fingerprint IS NULL AND receipt_etag IS NULL AND failure_code IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS rg_authoring_command_journal_lease_recovery_idx
    ON rg_authoring_command_journal (status, lease_until, updated_at);

CREATE TABLE IF NOT EXISTS rg_api_resource_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    spec_json TEXT NOT NULL,
    spec_fingerprint VARCHAR(128) NOT NULL
        CHECK (CHAR_LENGTH(spec_fingerprint) = 71 AND spec_fingerprint LIKE 'sha256:%'),
    connection_id VARCHAR(128) NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_resource_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id, revision),
    CONSTRAINT rg_api_resource_revisions_command_uq UNIQUE (command_id),
    CONSTRAINT rg_api_resource_revisions_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, resource_id, revision, strong_etag),
    CONSTRAINT rg_api_resource_revisions_state_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, resource_id, revision, strong_etag, state),
    CONSTRAINT rg_api_resource_revisions_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_journal (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_resource_revisions_state_ck CHECK (state IN ('STAGED', 'COMMITTED')),
    CONSTRAINT rg_api_resource_revisions_revision_ck CHECK (revision > 0),
    CONSTRAINT rg_api_resource_revisions_attempt_ck CHECK (attempt_no > 0),
    CONSTRAINT rg_api_resource_revisions_etag_ck CHECK
        (CHAR_LENGTH(strong_etag) >= 3 AND strong_etag LIKE '"%"' AND strong_etag NOT LIKE '"W/%')
);

CREATE INDEX IF NOT EXISTS rg_api_resource_revisions_connection_visibility_idx
    ON rg_api_resource_revisions
       (tenant_id, project_id, environment_id, connection_id, state, resource_id);
CREATE INDEX IF NOT EXISTS rg_api_resource_revisions_staging_cleanup_idx
    ON rg_api_resource_revisions (state, updated_at);

CREATE TABLE IF NOT EXISTS rg_api_resource_projection_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    descriptor_json TEXT NOT NULL,
    descriptor_fingerprint VARCHAR(128) NOT NULL
        CHECK (CHAR_LENGTH(descriptor_fingerprint) = 71 AND descriptor_fingerprint LIKE 'sha256:%'),
    descriptor_state VARCHAR(16) NOT NULL,
    design_contract_json TEXT NOT NULL,
    design_contract_fingerprint VARCHAR(128) NOT NULL
        CHECK (CHAR_LENGTH(design_contract_fingerprint) = 71 AND design_contract_fingerprint LIKE 'sha256:%'),
    design_contract_state VARCHAR(16) NOT NULL,
    operator_json TEXT NOT NULL,
    operator_fingerprint VARCHAR(128) NOT NULL
        CHECK (CHAR_LENGTH(operator_fingerprint) = 71 AND operator_fingerprint LIKE 'sha256:%'),
    operator_state VARCHAR(16) NOT NULL,
    set_fingerprint VARCHAR(128) NOT NULL
        CHECK (CHAR_LENGTH(set_fingerprint) = 71 AND set_fingerprint LIKE 'sha256:%'),
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
    revision_state VARCHAR(16) NOT NULL DEFAULT 'COMMITTED',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_resource_heads_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id),
    CONSTRAINT rg_api_resource_heads_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision, strong_etag, revision_state)
        REFERENCES rg_api_resource_revisions
            (tenant_id, project_id, environment_id, resource_id, revision, strong_etag, state),
    CONSTRAINT rg_api_resource_heads_projection_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision)
        REFERENCES rg_api_resource_projection_revisions
            (tenant_id, project_id, environment_id, resource_id, revision),
    CONSTRAINT rg_api_resource_heads_state_ck CHECK (revision_state = 'COMMITTED'),
    CONSTRAINT rg_api_resource_heads_etag_ck CHECK
        (CHAR_LENGTH(strong_etag) >= 3 AND strong_etag LIKE '"%"' AND strong_etag NOT LIKE '"W/%')
);
