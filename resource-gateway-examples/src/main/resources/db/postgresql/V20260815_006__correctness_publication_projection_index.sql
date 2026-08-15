-- Supports exact latest-publication projection without scanning a Definition's history.
CREATE INDEX IF NOT EXISTS rg_correctness_publication_definition_latest_idx
    ON rg_correctness_publications (
        tenant_id, organization_id, project_id, environment_id, region_id,
        definition_id, definition_revision, definition_fingerprint,
        committed_at DESC, publication_id DESC
    );
