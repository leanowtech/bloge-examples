CREATE TABLE IF NOT EXISTS rg_bm_implementation_binding (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region VARCHAR(64) NOT NULL,
    binding_id VARCHAR(512) NOT NULL,
    proposal_id VARCHAR(512) NOT NULL,
    proposal_revision BIGINT NOT NULL CHECK (proposal_revision > 0),
    request_fingerprint VARCHAR(71) NOT NULL
        CHECK (request_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    stored_json TEXT NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region, binding_id)
);

CREATE INDEX IF NOT EXISTS rg_bm_implementation_binding_proposal_idx
    ON rg_bm_implementation_binding (
        tenant_id, organization_id, project_id, environment_id, region,
        proposal_id, proposal_revision
    );
