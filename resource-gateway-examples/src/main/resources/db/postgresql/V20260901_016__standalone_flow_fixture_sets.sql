CREATE TABLE rg_authoring_standalone_fixture_identities (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, fixture_set_id)
);

CREATE TABLE rg_authoring_standalone_fixture_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    fixture_fingerprint VARCHAR(71) NOT NULL,
    subject_publication_id VARCHAR(128) NOT NULL,
    subject_revision BIGINT NOT NULL,
    subject_fingerprint VARCHAR(71) NOT NULL,
    generated_json TEXT NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_by VARCHAR(256) NOT NULL,
    PRIMARY KEY (tenant_id, project_id, environment_id, fixture_set_id, revision),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_etag_uq
        UNIQUE (tenant_id, project_id, environment_id, fixture_set_id, strong_etag),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_revision_etag_uq
        UNIQUE (tenant_id, project_id, environment_id, fixture_set_id,
                revision, strong_etag),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_exact_uq
        UNIQUE (tenant_id, project_id, environment_id, fixture_set_id, revision,
                fixture_fingerprint, strong_etag),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_identity_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, fixture_set_id)
        REFERENCES rg_authoring_standalone_fixture_identities
            (tenant_id, project_id, environment_id, fixture_set_id)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_standalone_fixture_revisions_flow_version_fk
        FOREIGN KEY (tenant_id, project_id, environment_id,
                     subject_publication_id, subject_revision)
        REFERENCES rg_authoring_flow_versions
            (tenant_id, project_id, environment_id, publication_id, revision)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_standalone_fixture_revisions_revision_ck CHECK (revision > 0),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_subject_revision_ck
        CHECK (subject_revision > 0),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_fingerprint_ck
        CHECK (CHAR_LENGTH(fixture_fingerprint) = 71
            AND fixture_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_standalone_fixture_revisions_subject_fingerprint_ck
        CHECK (CHAR_LENGTH(subject_fingerprint) = 71
            AND subject_fingerprint LIKE 'sha256:%')
);

CREATE TABLE rg_authoring_standalone_fixture_heads (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    fixture_fingerprint VARCHAR(71) NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    PRIMARY KEY (tenant_id, project_id, environment_id, fixture_set_id),
    CONSTRAINT rg_authoring_standalone_fixture_heads_revision_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, fixture_set_id,
                     revision, fixture_fingerprint, strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, fixture_fingerprint, strong_etag)
        ON DELETE RESTRICT
);

CREATE TABLE rg_authoring_standalone_fixture_commands (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    fixture_set_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    expected_mode VARCHAR(16) NOT NULL,
    expected_revision BIGINT,
    committed_revision BIGINT NOT NULL,
    receipt_json TEXT NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, actor_id,
                 fixture_set_id, idempotency_key),
    CONSTRAINT rg_authoring_standalone_fixture_commands_revision_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, fixture_set_id,
                     committed_revision, strong_etag)
        REFERENCES rg_authoring_standalone_fixture_revisions
            (tenant_id, project_id, environment_id, fixture_set_id,
             revision, strong_etag)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_standalone_fixture_commands_expected_ck CHECK (
        (expected_mode = 'CREATE' AND expected_revision IS NULL)
        OR (expected_mode = 'MATCH' AND expected_revision IS NOT NULL AND expected_revision > 0)
    ),
    CONSTRAINT rg_authoring_standalone_fixture_commands_revision_ck
        CHECK (committed_revision > 0),
    CONSTRAINT rg_authoring_standalone_fixture_commands_request_fingerprint_ck
        CHECK (CHAR_LENGTH(request_fingerprint) = 71
            AND request_fingerprint LIKE 'sha256:%')
);

CREATE INDEX rg_authoring_standalone_fixture_subject_idx
    ON rg_authoring_standalone_fixture_revisions
        (tenant_id, project_id, environment_id,
         subject_publication_id, subject_revision, subject_fingerprint,
         fixture_set_id, revision);
