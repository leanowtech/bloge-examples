-- Durable, payload-free World fidelity calibration history and drift state.
CREATE TABLE IF NOT EXISTS rg_world_fidelity_reports (
    tenant_id TEXT NOT NULL,
    target_fingerprint TEXT NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    report_fingerprint TEXT NOT NULL CHECK (report_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    contract_fingerprint TEXT NOT NULL CHECK (contract_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    world_slice_fingerprint TEXT NOT NULL CHECK (world_slice_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    implementation_fingerprint TEXT NOT NULL CHECK (implementation_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    sample_set_fingerprint TEXT NOT NULL CHECK (sample_set_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    outcome TEXT NOT NULL CHECK (outcome IN ('EQUIVALENT', 'DIFFERENT', 'UNKNOWN')),
    report_projection_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, target_fingerprint, report_fingerprint)
);
CREATE TABLE IF NOT EXISTS rg_world_fidelity_drift_heads (
    tenant_id TEXT NOT NULL,
    target_fingerprint TEXT NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    state TEXT NOT NULL CHECK (state IN ('CURRENT', 'SUSPECTED', 'CONFIRMED', 'REMEDIATING', 'ACCEPTED_DIVERGENCE')),
    report_fingerprint TEXT NOT NULL CHECK (report_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    contract_fingerprint TEXT NOT NULL CHECK (contract_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    world_slice_fingerprint TEXT NOT NULL CHECK (world_slice_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    implementation_fingerprint TEXT NOT NULL CHECK (implementation_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    sample_set_fingerprint TEXT NOT NULL CHECK (sample_set_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, target_fingerprint)
);
CREATE TABLE IF NOT EXISTS rg_world_fidelity_receipts (
    tenant_id TEXT NOT NULL,
    receipt_fingerprint TEXT NOT NULL CHECK (receipt_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    target_fingerprint TEXT NOT NULL CHECK (target_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, receipt_fingerprint)
);
CREATE INDEX IF NOT EXISTS rg_world_fidelity_reports_target_idx
    ON rg_world_fidelity_reports (tenant_id, target_fingerprint, created_at);
