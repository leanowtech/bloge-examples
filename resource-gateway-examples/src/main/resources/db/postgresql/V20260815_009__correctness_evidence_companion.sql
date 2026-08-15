-- Immutable payload-free correctness evidence lineage bound to existing suite-run evidence.
CREATE TABLE IF NOT EXISTS rg_correctness_evidence_companions (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region_id VARCHAR(128) NOT NULL,
    suite_run_id VARCHAR(512) NOT NULL,
    evidence_companion_id VARCHAR(512) NOT NULL,
    companion_fingerprint VARCHAR(80) NOT NULL
        CHECK (companion_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    publication_id VARCHAR(512) NOT NULL,
    publication_fingerprint VARCHAR(80) NOT NULL
        CHECK (publication_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    suite_evidence_fingerprint VARCHAR(80) NOT NULL
        CHECK (suite_evidence_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    canonical_json JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(512) NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region_id, suite_run_id
    ),
    UNIQUE (
        tenant_id, organization_id, project_id, environment_id, region_id,
        evidence_companion_id
    )
);

CREATE INDEX IF NOT EXISTS rg_correctness_evidence_publication_idx
    ON rg_correctness_evidence_companions (
        tenant_id, organization_id, project_id, environment_id, region_id,
        publication_id, created_at DESC, suite_run_id
    );
