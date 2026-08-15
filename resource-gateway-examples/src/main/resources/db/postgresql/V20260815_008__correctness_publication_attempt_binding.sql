-- Binds every newly committed correctness Publication to the exact Saga report that supplied its
-- source map. Existing rows remain NULL and are intentionally not inferred from fingerprints.
ALTER TABLE rg_correctness_publications
    ADD COLUMN IF NOT EXISTS publication_attempt_id VARCHAR(512);

CREATE INDEX IF NOT EXISTS rg_correctness_publication_attempt_binding_idx
    ON rg_correctness_publications (
        tenant_id, organization_id, project_id, environment_id, region_id,
        publication_attempt_id
    );
