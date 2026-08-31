CREATE TABLE rg_authoring_flow_publication_identities (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    publication_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, flow_id),
    CONSTRAINT rg_authoring_flow_publication_identities_publication_uq
        UNIQUE (tenant_id, project_id, environment_id, publication_id),
    CONSTRAINT rg_authoring_flow_publication_identities_exact_uq
        UNIQUE (tenant_id, project_id, environment_id, flow_id, publication_id),
    CONSTRAINT rg_authoring_flow_publication_identities_flow_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, flow_id)
        REFERENCES rg_authoring_flow_identities
            (tenant_id, project_id, environment_id, flow_id)
        ON DELETE RESTRICT
);

CREATE TABLE rg_authoring_flow_versions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    publication_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    version_fingerprint VARCHAR(71) NOT NULL,
    source_draft_id VARCHAR(128) NOT NULL,
    source_revision BIGINT NOT NULL,
    source_fingerprint VARCHAR(71) NOT NULL,
    version_json TEXT NOT NULL,
    receipt_json TEXT NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_by VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, project_id, environment_id, publication_id, revision),
    CONSTRAINT rg_authoring_flow_versions_exact_uq
        UNIQUE (tenant_id, project_id, environment_id, flow_id, publication_id, revision,
                version_fingerprint, source_draft_id, source_revision, source_fingerprint),
    CONSTRAINT rg_authoring_flow_versions_identity_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, flow_id, publication_id)
        REFERENCES rg_authoring_flow_publication_identities
            (tenant_id, project_id, environment_id, flow_id, publication_id)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_flow_versions_source_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, flow_id, source_revision)
        REFERENCES rg_authoring_flow_revisions
            (tenant_id, project_id, environment_id, flow_id, revision)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_flow_versions_revision_ck CHECK (revision > 0),
    CONSTRAINT rg_authoring_flow_versions_source_revision_ck CHECK (source_revision > 0),
    CONSTRAINT rg_authoring_flow_versions_fingerprint_ck
        CHECK (CHAR_LENGTH(version_fingerprint) = 71
            AND version_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_flow_versions_source_fingerprint_ck
        CHECK (CHAR_LENGTH(source_fingerprint) = 71
            AND source_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_flow_versions_status_ck CHECK (status = 'PUBLISHED')
);

CREATE TABLE rg_authoring_flow_publish_commands (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(256) NOT NULL,
    flow_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    publication_id VARCHAR(128) NOT NULL,
    committed_revision BIGINT NOT NULL,
    receipt_json TEXT NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, project_id, environment_id, actor_id, flow_id, idempotency_key),
    CONSTRAINT rg_authoring_flow_publish_commands_version_fk
        FOREIGN KEY (tenant_id, project_id, environment_id, publication_id, committed_revision)
        REFERENCES rg_authoring_flow_versions
            (tenant_id, project_id, environment_id, publication_id, revision)
        ON DELETE RESTRICT,
    CONSTRAINT rg_authoring_flow_publish_commands_revision_ck CHECK (committed_revision > 0),
    CONSTRAINT rg_authoring_flow_publish_commands_request_fingerprint_ck
        CHECK (CHAR_LENGTH(request_fingerprint) = 71
            AND request_fingerprint LIKE 'sha256:%')
);
