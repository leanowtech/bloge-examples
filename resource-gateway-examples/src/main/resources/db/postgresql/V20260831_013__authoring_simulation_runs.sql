CREATE TABLE rg_authoring_simulation_runs (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(71) NOT NULL,
    status VARCHAR(16) NOT NULL,
    run_json TEXT,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, project_id, environment_id, run_id),
    CONSTRAINT rg_authoring_simulation_runs_idempotency_uq
        UNIQUE (tenant_id, project_id, environment_id, idempotency_key),
    CONSTRAINT rg_authoring_simulation_runs_status_ck
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED')),
    CONSTRAINT rg_authoring_simulation_runs_fingerprint_ck
        CHECK (CHAR_LENGTH(request_fingerprint) = 71
            AND request_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_authoring_simulation_runs_completion_ck
        CHECK ((status = 'RUNNING' AND run_json IS NULL AND ended_at IS NULL)
            OR (status <> 'RUNNING' AND run_json IS NOT NULL AND ended_at IS NOT NULL))
);

CREATE INDEX rg_authoring_simulation_runs_recovery_idx
    ON rg_authoring_simulation_runs (status, lease_until,
        tenant_id, project_id, environment_id, run_id);
