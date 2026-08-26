-- S3-A: durable world-draft candidates, redacted payload vault, and audit trail.
-- PostgreSQL is the production dialect. Payload columns are JSONB; the vault
-- expiry index lets retention and metadata admission run before payload reads.

CREATE TABLE IF NOT EXISTS rg_world_draft_candidates (
    candidate_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    candidate_fingerprint TEXT NOT NULL CHECK (candidate_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    canonical_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, candidate_id)
);

CREATE TABLE IF NOT EXISTS rg_world_draft_redacted_payloads (
    tenant_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    artifact_revision BIGINT NOT NULL CHECK (artifact_revision > 0),
    request_fingerprint TEXT NOT NULL CHECK (request_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    response_fingerprint TEXT NOT NULL CHECK (response_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    pair_fingerprint TEXT NOT NULL CHECK (pair_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    protected_payload TEXT NOT NULL,
    payload_key_id TEXT NOT NULL,
    payload_commitment TEXT NOT NULL CHECK (payload_commitment ~ '^sha256:[a-f0-9]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    retention_status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (retention_status IN ('DRAFT', 'PINNED', 'REVOKED')),
    published_world_fingerprint TEXT NULL CHECK (published_world_fingerprint IS NULL OR published_world_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    published_rule_fingerprint TEXT NULL CHECK (published_rule_fingerprint IS NULL OR published_rule_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    publication_receipt_fingerprint TEXT NULL CHECK (publication_receipt_fingerprint IS NULL OR publication_receipt_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    revoked_at TIMESTAMPTZ NULL,
    PRIMARY KEY (tenant_id, candidate_id, artifact_revision,
                 request_fingerprint, response_fingerprint, pair_fingerprint)
);

CREATE INDEX IF NOT EXISTS rg_world_draft_redacted_payloads_expiry_idx
    ON rg_world_draft_redacted_payloads (expires_at);
CREATE INDEX IF NOT EXISTS rg_world_draft_redacted_payloads_candidate_idx
    ON rg_world_draft_redacted_payloads (tenant_id, candidate_id, artifact_revision);
CREATE INDEX IF NOT EXISTS rg_world_draft_redacted_payloads_retention_idx
    ON rg_world_draft_redacted_payloads (retention_status, expires_at);

CREATE TABLE IF NOT EXISTS rg_world_draft_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    success BOOLEAN NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS rg_world_draft_audit_candidate_idx
    ON rg_world_draft_audit (tenant_id, candidate_id, recorded_at);

CREATE TABLE IF NOT EXISTS rg_world_draft_assets (
    tenant_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    materialization_fingerprint TEXT NOT NULL CHECK (materialization_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    materialization_revision BIGINT NOT NULL CHECK (materialization_revision > 0),
    world_fingerprint TEXT NOT NULL CHECK (world_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    rule_fingerprint TEXT NOT NULL CHECK (rule_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    canonical_json JSONB NOT NULL,
    publication_receipt_fingerprint TEXT NULL CHECK (publication_receipt_fingerprint IS NULL OR publication_receipt_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, candidate_id, materialization_fingerprint)
);
CREATE INDEX IF NOT EXISTS rg_world_draft_assets_status_idx
    ON rg_world_draft_assets (tenant_id, status, updated_at);

CREATE TABLE IF NOT EXISTS rg_world_draft_authority_receipts (
    tenant_id TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    receipt_kind TEXT NOT NULL CHECK (receipt_kind IN ('APPROVAL', 'PUBLICATION')),
    receipt_fingerprint TEXT NOT NULL CHECK (receipt_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, candidate_id, receipt_kind, receipt_fingerprint)
);
