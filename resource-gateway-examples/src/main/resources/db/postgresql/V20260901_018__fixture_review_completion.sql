ALTER TABLE rg_authoring_fixture_review_requests
    DROP CONSTRAINT rg_authoring_fixture_review_requests_status_ck;

ALTER TABLE rg_authoring_fixture_review_requests
    ADD COLUMN completed_revision BIGINT;

ALTER TABLE rg_authoring_fixture_review_requests
    ADD COLUMN completed_fingerprint VARCHAR(71);

ALTER TABLE rg_authoring_fixture_review_requests
    ADD COLUMN completed_strong_etag VARCHAR(256);

ALTER TABLE rg_authoring_fixture_review_requests
    ADD COLUMN completed_by VARCHAR(256);

ALTER TABLE rg_authoring_fixture_review_requests
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE rg_authoring_fixture_review_requests
    ADD CONSTRAINT rg_authoring_fixture_review_requests_completed_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         completed_revision, completed_fingerprint, completed_strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, fixture_fingerprint, strong_etag);

ALTER TABLE rg_authoring_fixture_review_requests
    ADD CONSTRAINT rg_authoring_fixture_review_requests_status_ck CHECK
        ((status = 'PENDING' AND completed_revision IS NULL
          AND completed_fingerprint IS NULL AND completed_strong_etag IS NULL
          AND completed_by IS NULL AND completed_at IS NULL)
         OR
         (status = 'COMPLETED' AND completed_revision IS NOT NULL
          AND completed_fingerprint IS NOT NULL AND completed_strong_etag IS NOT NULL
          AND completed_by IS NOT NULL AND completed_at IS NOT NULL));

CREATE TABLE rg_authoring_fixture_review_commands (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    review_request_id VARCHAR(128) NOT NULL,
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
    CONSTRAINT rg_authoring_fixture_review_commands_request_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, review_request_id)
        REFERENCES rg_authoring_fixture_review_requests
            (tenant_id, project_id, environment_id, review_request_id),
    CONSTRAINT rg_authoring_fixture_review_commands_source_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         source_revision, source_fingerprint, source_strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, fixture_fingerprint, strong_etag),
    CONSTRAINT rg_authoring_fixture_review_commands_committed_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, fixture_set_id,
         committed_revision, strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, strong_etag),
    CONSTRAINT rg_authoring_fixture_review_commands_revision_ck CHECK
        (source_revision > 0 AND source_status_revision > 0
         AND committed_revision > source_revision),
    CONSTRAINT rg_authoring_fixture_review_commands_request_fp_ck CHECK
        (CHAR_LENGTH(request_fingerprint) = 71 AND request_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_fixture_review_commands_source_fp_ck CHECK
        (CHAR_LENGTH(source_fingerprint) = 71 AND source_fingerprint LIKE 'sha256:%')
);
