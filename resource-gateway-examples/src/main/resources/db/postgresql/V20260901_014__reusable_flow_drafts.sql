CREATE TABLE rg_authoring_flow_identities (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    draft_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, flow_id),
    CONSTRAINT rg_authoring_flow_identities_draft_uq
        UNIQUE (tenant_id, project_id, environment_id, draft_id),
    CONSTRAINT rg_authoring_flow_identities_exact_uq
        UNIQUE (tenant_id, project_id, environment_id, flow_id, draft_id)
);

CREATE TABLE rg_authoring_flow_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    draft_id VARCHAR(128) NOT NULL,
    content_fingerprint VARCHAR(71) NOT NULL,
    draft_json TEXT NOT NULL,
    receipt_json TEXT NOT NULL,
    strong_etag VARCHAR(258) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, flow_id, revision),
    CONSTRAINT rg_authoring_flow_revisions_etag_uq
        UNIQUE (tenant_id, project_id, environment_id, flow_id, strong_etag),
    CONSTRAINT rg_authoring_flow_revisions_command_ref_uq
        UNIQUE (tenant_id, project_id, environment_id, flow_id, revision, strong_etag),
    CONSTRAINT rg_authoring_flow_revisions_exact_uq
        UNIQUE (tenant_id, project_id, environment_id, flow_id, revision,
                draft_id, content_fingerprint, strong_etag),
    CONSTRAINT rg_authoring_flow_revisions_identity_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, flow_id, draft_id)
        REFERENCES rg_authoring_flow_identities
            (tenant_id, project_id, environment_id, flow_id, draft_id)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_flow_revisions_revision_ck CHECK (revision > 0),
    CONSTRAINT rg_authoring_flow_revisions_content_fingerprint_ck
        CHECK (CHAR_LENGTH(content_fingerprint) = 71
            AND content_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_flow_revisions_etag_ck
        CHECK (CHAR_LENGTH(strong_etag) BETWEEN 3 AND 258
            AND strong_etag LIKE '"%"')
);

CREATE TABLE rg_authoring_flow_heads (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    draft_id VARCHAR(128) NOT NULL,
    content_fingerprint VARCHAR(71) NOT NULL,
    strong_etag VARCHAR(258) NOT NULL,
    PRIMARY KEY (tenant_id, project_id, environment_id, flow_id),
    CONSTRAINT rg_authoring_flow_heads_revision_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, flow_id, revision,
                     draft_id, content_fingerprint, strong_etag)
        REFERENCES rg_authoring_flow_revisions
            (tenant_id, project_id, environment_id, flow_id, revision,
             draft_id, content_fingerprint, strong_etag)
        ON DELETE RESTRICT
);

CREATE TABLE rg_authoring_flow_commands (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    expected_mode VARCHAR(16) NOT NULL,
    expected_revision BIGINT,
    committed_revision BIGINT NOT NULL,
    receipt_json TEXT NOT NULL,
    strong_etag VARCHAR(258) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, actor_id, flow_id, idempotency_key),
    CONSTRAINT rg_authoring_flow_commands_revision_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, flow_id,
                     committed_revision, strong_etag)
        REFERENCES rg_authoring_flow_revisions
            (tenant_id, project_id, environment_id, flow_id, revision, strong_etag)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_flow_commands_expected_ck
        CHECK ((expected_mode = 'CREATE' AND expected_revision IS NULL)
            OR (expected_mode = 'MATCH' AND expected_revision IS NOT NULL
                AND expected_revision > 0)),
    CONSTRAINT rg_authoring_flow_commands_committed_revision_ck
        CHECK (committed_revision > 0),
    CONSTRAINT rg_authoring_flow_commands_request_fingerprint_ck
        CHECK (CHAR_LENGTH(request_fingerprint) = 71
            AND request_fingerprint LIKE 'sha256:%')
);
