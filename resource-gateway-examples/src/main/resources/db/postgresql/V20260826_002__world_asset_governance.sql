-- Stage 1-D2b: metadata-first governance columns for heads and immutable revisions.
-- Existing D1 write paths use the same safe defaults in the repository.

ALTER TABLE rg_world_catalog_heads
    ADD COLUMN IF NOT EXISTS payload_origin VARCHAR(32) NOT NULL DEFAULT 'SYNTHETIC';
ALTER TABLE rg_world_catalog_heads
    ADD COLUMN IF NOT EXISTS security_classification VARCHAR(32) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE rg_world_catalog_heads
    ADD COLUMN IF NOT EXISTS retention_expires_at TIMESTAMP NULL;
ALTER TABLE rg_world_catalog_heads
    ADD COLUMN IF NOT EXISTS access_policy_ref VARCHAR(512) NOT NULL DEFAULT 'builtin:synthetic-public';
ALTER TABLE rg_world_catalog_heads
    ADD COLUMN IF NOT EXISTS approval_ref VARCHAR(512) NULL;
ALTER TABLE rg_world_catalog_heads
    ADD COLUMN IF NOT EXISTS governance_fingerprint VARCHAR(80);

ALTER TABLE rg_world_catalog_revisions
    ADD COLUMN IF NOT EXISTS payload_origin VARCHAR(32) NOT NULL DEFAULT 'SYNTHETIC';
ALTER TABLE rg_world_catalog_revisions
    ADD COLUMN IF NOT EXISTS security_classification VARCHAR(32) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE rg_world_catalog_revisions
    ADD COLUMN IF NOT EXISTS retention_expires_at TIMESTAMP NULL;
ALTER TABLE rg_world_catalog_revisions
    ADD COLUMN IF NOT EXISTS access_policy_ref VARCHAR(512) NOT NULL DEFAULT 'builtin:synthetic-public';
ALTER TABLE rg_world_catalog_revisions
    ADD COLUMN IF NOT EXISTS approval_ref VARCHAR(512) NULL;
ALTER TABLE rg_world_catalog_revisions
    ADD COLUMN IF NOT EXISTS governance_fingerprint VARCHAR(80);
