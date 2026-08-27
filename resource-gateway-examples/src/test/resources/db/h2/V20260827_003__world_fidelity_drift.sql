-- Test-only H2 schema; production uses the PostgreSQL migration with JSONB.
CREATE TABLE rg_world_fidelity_reports (
    tenant_id VARCHAR(512) NOT NULL,
    target_fingerprint VARCHAR(80) NOT NULL,
    report_fingerprint VARCHAR(80) NOT NULL,
    contract_fingerprint VARCHAR(80) NOT NULL,
    world_slice_fingerprint VARCHAR(80) NOT NULL,
    implementation_fingerprint VARCHAR(80) NOT NULL,
    sample_set_fingerprint VARCHAR(80) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    report_projection_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, target_fingerprint, report_fingerprint)
);
CREATE TABLE rg_world_fidelity_drift_heads (
    tenant_id VARCHAR(512) NOT NULL,
    target_fingerprint VARCHAR(80) NOT NULL,
    state VARCHAR(32) NOT NULL,
    report_fingerprint VARCHAR(80) NOT NULL,
    contract_fingerprint VARCHAR(80) NOT NULL,
    world_slice_fingerprint VARCHAR(80) NOT NULL,
    implementation_fingerprint VARCHAR(80) NOT NULL,
    sample_set_fingerprint VARCHAR(80) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, target_fingerprint)
);
CREATE TABLE rg_world_fidelity_receipts (
    tenant_id VARCHAR(512) NOT NULL,
    receipt_fingerprint VARCHAR(80) NOT NULL,
    target_fingerprint VARCHAR(80) NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, receipt_fingerprint)
);
CREATE INDEX rg_world_fidelity_reports_target_idx
    ON rg_world_fidelity_reports (tenant_id, target_fingerprint, created_at);
