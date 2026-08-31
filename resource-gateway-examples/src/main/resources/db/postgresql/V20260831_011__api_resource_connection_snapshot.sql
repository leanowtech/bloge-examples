-- Bind every API Resource revision to the exact committed Connection metadata
-- snapshot used to compile its runtime and visual projections.

ALTER TABLE rg_api_resource_revisions
    ADD COLUMN connection_revision BIGINT;
ALTER TABLE rg_api_resource_revisions
    ADD COLUMN connection_metadata_fingerprint VARCHAR(71);

-- V001-V010 never recorded which Connection revision compiled an existing
-- Resource. The current Connection head is not evidence of that historical
-- fact, so this migration deliberately does not backfill it. A deployment
-- with legacy Resource rows must export/re-author them through a separately
-- audited migration before applying V011; the NOT NULL DDL below otherwise
-- fails closed instead of inventing provenance.
ALTER TABLE rg_api_resource_revisions
    ALTER COLUMN connection_revision SET NOT NULL;
ALTER TABLE rg_api_resource_revisions
    ALTER COLUMN connection_metadata_fingerprint SET NOT NULL;

ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_connection_revision_ck CHECK
        (connection_revision > 0);
ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_connection_fingerprint_ck CHECK
        (CHAR_LENGTH(connection_metadata_fingerprint) = 71
         AND connection_metadata_fingerprint LIKE 'sha256:%'
         AND LOWER(connection_metadata_fingerprint) = connection_metadata_fingerprint
         AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             SUBSTRING(connection_metadata_fingerprint, 8, 64), '0', ''), '1', ''), '2', ''), '3', ''),
             '4', ''), '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), 'a', ''),
             'b', ''), 'c', ''), 'd', ''), 'e', ''), 'f', '') = '');

CREATE INDEX IF NOT EXISTS rg_api_resource_revisions_connection_snapshot_idx
    ON rg_api_resource_revisions
       (tenant_id, project_id, environment_id, connection_id,
        connection_revision, connection_metadata_fingerprint, state);
