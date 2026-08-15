-- Proposed-only outcome calibration and immutable ANEKE governance feedback projections.
CREATE TABLE IF NOT EXISTS rg_outcome_calibration_proposals (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(512) NOT NULL,
    proposal_fingerprint VARCHAR(80) NOT NULL
        CHECK (proposal_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    status VARCHAR(32) NOT NULL CHECK (status = 'PROPOSED'),
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(512) NOT NULL,
    publication_id VARCHAR(512) NOT NULL,
    publication_fingerprint VARCHAR(80) NOT NULL
        CHECK (publication_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    suite_run_id VARCHAR(512) NOT NULL,
    evidence_companion_id VARCHAR(512) NOT NULL,
    evidence_companion_fingerprint VARCHAR(80) NOT NULL
        CHECK (evidence_companion_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    mismatch_kind VARCHAR(64) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    owner_id VARCHAR(512) NOT NULL,
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, proposal_id
    )
);

CREATE INDEX IF NOT EXISTS rg_outcome_calibration_review_queue_idx
    ON rg_outcome_calibration_proposals (
        tenant_id, organization_id, project_id, environment_id, region_id,
        status, owner_id, created_at DESC, proposal_id
    );

CREATE INDEX IF NOT EXISTS rg_outcome_calibration_source_idx
    ON rg_outcome_calibration_proposals (
        tenant_id, organization_id, project_id, environment_id, region_id,
        publication_id, suite_run_id, created_at DESC
    );

CREATE TABLE IF NOT EXISTS rg_correctness_governance_feedback (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    feedback_id VARCHAR(512) NOT NULL,
    feedback_fingerprint VARCHAR(80) NOT NULL
        CHECK (feedback_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    publication_id VARCHAR(512) NOT NULL,
    publication_fingerprint VARCHAR(80) NOT NULL
        CHECK (publication_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    source_system VARCHAR(128) NOT NULL,
    source_decision_id VARCHAR(512) NOT NULL,
    source_decision_revision BIGINT NOT NULL CHECK (source_decision_revision > 0),
    source_decision_fingerprint VARCHAR(80) NOT NULL
        CHECK (source_decision_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    decision VARCHAR(32) NOT NULL,
    canonical_json JSONB NOT NULL,
    produced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, feedback_id
    ),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region_id,
        source_system, source_decision_id, source_decision_revision
    )
);

CREATE INDEX IF NOT EXISTS rg_correctness_governance_publication_idx
    ON rg_correctness_governance_feedback (
        tenant_id, organization_id, project_id, environment_id, region_id,
        publication_id, publication_fingerprint, produced_at DESC,
        source_decision_revision DESC
    );
