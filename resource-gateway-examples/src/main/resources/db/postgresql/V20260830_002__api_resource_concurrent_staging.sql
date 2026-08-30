-- Slice 2A-2b API Resource concurrent staging migration.
-- Apply V20260830_001__api_resource_authoring.sql before this migration.
-- Keep this migration executable by PostgreSQL and H2 MODE=PostgreSQL.
-- A logical revision is now identified by its command provenance so two
-- commands may stage the same next revision before one command wins the head.

-- Existing rows carry the provenance on revisions.  Copy it before replacing
-- the revision/projection keys and before making the new columns non-null.
ALTER TABLE rg_api_resource_projection_revisions
    ADD COLUMN IF NOT EXISTS command_id VARCHAR(128);
ALTER TABLE rg_api_resource_heads
    ADD COLUMN IF NOT EXISTS command_id VARCHAR(128);

UPDATE rg_api_resource_projection_revisions
   SET command_id = (
       SELECT r.command_id
         FROM rg_api_resource_revisions r
        WHERE r.tenant_id = rg_api_resource_projection_revisions.tenant_id
          AND r.project_id = rg_api_resource_projection_revisions.project_id
          AND r.environment_id = rg_api_resource_projection_revisions.environment_id
          AND r.resource_id = rg_api_resource_projection_revisions.resource_id
          AND r.revision = rg_api_resource_projection_revisions.revision
   );
UPDATE rg_api_resource_heads
   SET command_id = (
       SELECT r.command_id
         FROM rg_api_resource_revisions r
        WHERE r.tenant_id = rg_api_resource_heads.tenant_id
          AND r.project_id = rg_api_resource_heads.project_id
          AND r.environment_id = rg_api_resource_heads.environment_id
          AND r.resource_id = rg_api_resource_heads.resource_id
          AND r.revision = rg_api_resource_heads.revision
          AND r.strong_etag = rg_api_resource_heads.strong_etag
          AND r.state = rg_api_resource_heads.revision_state
   );

ALTER TABLE rg_api_resource_projection_revisions
    ALTER COLUMN command_id SET NOT NULL;
ALTER TABLE rg_api_resource_heads
    ALTER COLUMN command_id SET NOT NULL;

-- All foreign keys that point at the old keys must be removed first.
ALTER TABLE rg_api_resource_heads
    DROP CONSTRAINT IF EXISTS rg_api_resource_heads_revision_fk;
ALTER TABLE rg_api_resource_heads
    DROP CONSTRAINT IF EXISTS rg_api_resource_heads_projection_fk;
ALTER TABLE rg_api_resource_projection_revisions
    DROP CONSTRAINT IF EXISTS rg_api_resource_projection_revisions_revision_fk;

-- The old uniqueness rules also serialized concurrent staging, so replace
-- them together with the primary keys.
ALTER TABLE rg_api_resource_projection_revisions
    DROP CONSTRAINT IF EXISTS rg_api_resource_projection_revisions_pk;
ALTER TABLE rg_api_resource_revisions
    DROP CONSTRAINT IF EXISTS rg_api_resource_revisions_pk;
ALTER TABLE rg_api_resource_revisions
    DROP CONSTRAINT IF EXISTS rg_api_resource_revisions_etag_uq;
ALTER TABLE rg_api_resource_revisions
    DROP CONSTRAINT IF EXISTS rg_api_resource_revisions_state_etag_uq;

ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id, revision, command_id);
ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, resource_id, revision, command_id, strong_etag);
ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_state_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, resource_id, revision, command_id, strong_etag, state);

ALTER TABLE rg_api_resource_projection_revisions
    ADD CONSTRAINT rg_api_resource_projection_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, resource_id, revision, command_id);
ALTER TABLE rg_api_resource_projection_revisions
    ADD CONSTRAINT rg_api_resource_projection_revisions_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision, command_id)
        REFERENCES rg_api_resource_revisions
            (tenant_id, project_id, environment_id, resource_id, revision, command_id)
        ON DELETE CASCADE;

ALTER TABLE rg_api_resource_heads
    ADD CONSTRAINT rg_api_resource_heads_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision, command_id,
         strong_etag, revision_state)
        REFERENCES rg_api_resource_revisions
            (tenant_id, project_id, environment_id, resource_id, revision, command_id,
             strong_etag, state);
ALTER TABLE rg_api_resource_heads
    ADD CONSTRAINT rg_api_resource_heads_projection_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, resource_id, revision, command_id)
        REFERENCES rg_api_resource_projection_revisions
            (tenant_id, project_id, environment_id, resource_id, revision, command_id);
