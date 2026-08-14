CREATE TABLE IF NOT EXISTS rg_bm_proposal_simulation (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region VARCHAR(64) NOT NULL,
    proposal_id VARCHAR(512) NOT NULL,
    proposal_revision BIGINT NOT NULL CHECK (proposal_revision > 0),
    simulation_id VARCHAR(512) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
    lease_owner VARCHAR(512) NOT NULL,
    lease_epoch BIGINT NOT NULL CHECK (lease_epoch > 0),
    lease_expires_at VARCHAR(64) NOT NULL,
    result_json TEXT,
    last_failure_code VARCHAR(256) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region,
        proposal_id, proposal_revision
    ),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region,
        simulation_id
    )
);

CREATE INDEX IF NOT EXISTS idx_rg_bm_proposal_simulation_status
    ON rg_bm_proposal_simulation (
        tenant_id, organization_id, project_id, environment_id, region,
        status, lease_expires_at
    );
