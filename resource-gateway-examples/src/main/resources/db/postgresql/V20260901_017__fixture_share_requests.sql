CREATE TABLE rg_authoring_fixture_share_commands (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    source_revision BIGINT NOT NULL,
    source_fingerprint VARCHAR(71) NOT NULL,
    source_status_revision BIGINT NOT NULL,
    source_strong_etag VARCHAR(256) NOT NULL,
    committed_revision BIGINT NOT NULL,
    receipt_json TEXT NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, actor_id,
                 fixture_set_id, idempotency_key),
    CONSTRAINT rg_authoring_fixture_share_commands_source_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         source_revision, source_fingerprint, source_strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, fixture_fingerprint, strong_etag),
    CONSTRAINT rg_authoring_fixture_share_commands_derived_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         committed_revision, strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, strong_etag),
    CONSTRAINT rg_authoring_fixture_share_commands_revision_ck CHECK
        (source_revision > 0 AND source_status_revision > 0
         AND committed_revision > source_revision),
    CONSTRAINT rg_authoring_fixture_share_commands_request_fp_ck CHECK
        (CHAR_LENGTH(request_fingerprint) = 71 AND request_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_fixture_share_commands_source_fp_ck CHECK
        (CHAR_LENGTH(source_fingerprint) = 71 AND source_fingerprint LIKE 'sha256:%')
);

CREATE TABLE rg_authoring_fixture_review_requests (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    review_request_id VARCHAR(128) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    source_revision BIGINT NOT NULL,
    source_fingerprint VARCHAR(71) NOT NULL,
    source_status_revision BIGINT NOT NULL,
    source_strong_etag VARCHAR(256) NOT NULL,
    derived_revision BIGINT NOT NULL,
    derived_fingerprint VARCHAR(71) NOT NULL,
    derived_status_revision BIGINT NOT NULL,
    derived_strong_etag VARCHAR(256) NOT NULL,
    policy_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, review_request_id),
    CONSTRAINT rg_authoring_fixture_review_requests_source_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         source_revision, source_fingerprint, source_strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, fixture_fingerprint, strong_etag),
    CONSTRAINT rg_authoring_fixture_review_requests_derived_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         derived_revision, derived_fingerprint, derived_strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, fixture_fingerprint, strong_etag),
    CONSTRAINT rg_authoring_fixture_review_requests_status_ck CHECK
        (status = 'PENDING'),
    CONSTRAINT rg_authoring_fixture_review_requests_revision_ck CHECK
        (source_revision > 0 AND source_status_revision > 0
         AND derived_revision > source_revision AND derived_status_revision > 0),
    CONSTRAINT rg_authoring_fixture_review_requests_source_fp_ck CHECK
        (CHAR_LENGTH(source_fingerprint) = 71 AND source_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_fixture_review_requests_derived_fp_ck CHECK
        (CHAR_LENGTH(derived_fingerprint) = 71 AND derived_fingerprint LIKE 'sha256:%')
);

CREATE INDEX rg_authoring_fixture_review_queue_idx
    ON rg_authoring_fixture_review_requests
        (tenant_id, project_id, environment_id, status, created_at, review_request_id);
