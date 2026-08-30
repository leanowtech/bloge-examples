-- Forward-only closure for immutable attempt provenance and takeover history.
-- V009 keeps every attempt but the compact connection head/binding projections
-- still point at a mutable command/revision identity.  This migration makes
-- those projections identify the exact committed attempt as well.

ALTER TABLE rg_authoring_command_attempts
    DROP CONSTRAINT rg_authoring_command_attempts_status_ck;
ALTER TABLE rg_authoring_command_attempts
    ADD CONSTRAINT rg_authoring_command_attempts_status_ck CHECK
        (status IN ('PREPARING', 'SUPERSEDED', 'COMMITTED', 'FAILED'));

-- An immutable attempt is subordinate to the journal command coordinate.  The
-- journal row is inserted first by every writer; an unverifiable legacy row
-- therefore fails this DDL rather than acquiring invented ownership.
ALTER TABLE rg_authoring_command_attempts
    ADD CONSTRAINT rg_authoring_command_attempts_journal_fk
        FOREIGN KEY (command_id) REFERENCES rg_authoring_command_journal (command_id);

ALTER TABLE rg_api_connection_heads
    DROP CONSTRAINT rg_api_connection_heads_revision_fk;
ALTER TABLE rg_api_connection_secret_bindings
    DROP CONSTRAINT rg_api_connection_secret_bindings_revision_fk;

ALTER TABLE rg_api_connection_heads
    ADD COLUMN attempt_no INTEGER;
ALTER TABLE rg_api_connection_heads
    ADD COLUMN attempt_token VARCHAR(128);
ALTER TABLE rg_api_connection_secret_bindings
    ADD COLUMN attempt_no INTEGER;
ALTER TABLE rg_api_connection_secret_bindings
    ADD COLUMN attempt_token VARCHAR(128);

-- The compact projections only admit committed rows.  V009's committed-row
-- uniqueness makes this scalar lookup unambiguous; a missing or ambiguous
-- provenance leaves NULL and the following NOT NULL DDL fails closed.
UPDATE rg_api_connection_heads h
   SET attempt_no = (SELECT r.attempt_no
                       FROM rg_api_connection_revisions r
                      WHERE r.tenant_id = h.tenant_id
                        AND r.project_id = h.project_id
                        AND r.environment_id = h.environment_id
                        AND r.connection_id = h.connection_id
                        AND r.revision = h.revision
                        AND r.command_id = h.command_id
                        AND r.strong_etag = h.strong_etag
                        AND r.state = h.revision_state),
       attempt_token = (SELECT r.attempt_token
                          FROM rg_api_connection_revisions r
                         WHERE r.tenant_id = h.tenant_id
                           AND r.project_id = h.project_id
                           AND r.environment_id = h.environment_id
                           AND r.connection_id = h.connection_id
                           AND r.revision = h.revision
                           AND r.command_id = h.command_id
                           AND r.strong_etag = h.strong_etag
                           AND r.state = h.revision_state);

UPDATE rg_api_connection_secret_bindings b
   SET attempt_no = (SELECT r.attempt_no
                       FROM rg_api_connection_revisions r
                      WHERE r.tenant_id = b.tenant_id
                        AND r.project_id = b.project_id
                        AND r.environment_id = b.environment_id
                        AND r.connection_id = b.connection_id
                        AND r.revision = b.revision
                        AND r.command_id = b.command_id
                        AND r.state = b.revision_state),
       attempt_token = (SELECT r.attempt_token
                          FROM rg_api_connection_revisions r
                         WHERE r.tenant_id = b.tenant_id
                           AND r.project_id = b.project_id
                           AND r.environment_id = b.environment_id
                           AND r.connection_id = b.connection_id
                           AND r.revision = b.revision
                           AND r.command_id = b.command_id
                           AND r.state = b.revision_state);

ALTER TABLE rg_api_connection_heads
    ALTER COLUMN attempt_no SET NOT NULL;
ALTER TABLE rg_api_connection_heads
    ALTER COLUMN attempt_token SET NOT NULL;
ALTER TABLE rg_api_connection_secret_bindings
    ALTER COLUMN attempt_no SET NOT NULL;
ALTER TABLE rg_api_connection_secret_bindings
    ALTER COLUMN attempt_token SET NOT NULL;

ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_committed_ref_uq;
ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_state_etag_uq;
ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_committed_ref_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         attempt_no, attempt_token, state);
ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_state_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         attempt_no, attempt_token, strong_etag, state);

ALTER TABLE rg_api_connection_heads
    ADD CONSTRAINT rg_api_connection_heads_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         attempt_no, attempt_token, strong_etag, revision_state)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id,
             attempt_no, attempt_token, strong_etag, state);
ALTER TABLE rg_api_connection_secret_bindings
    ADD CONSTRAINT rg_api_connection_secret_bindings_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         attempt_no, attempt_token, revision_state)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id,
             attempt_no, attempt_token, state);

ALTER TABLE rg_api_connection_heads
    ADD CONSTRAINT rg_api_connection_heads_attempt_ck CHECK (attempt_no > 0);
ALTER TABLE rg_api_connection_secret_bindings
    ADD CONSTRAINT rg_api_connection_secret_bindings_attempt_ck CHECK (attempt_no > 0);

CREATE INDEX IF NOT EXISTS rg_api_connection_heads_attempt_idx
    ON rg_api_connection_heads (command_id, attempt_no, attempt_token);
CREATE INDEX IF NOT EXISTS rg_api_connection_secret_bindings_attempt_idx
    ON rg_api_connection_secret_bindings (command_id, attempt_no, attempt_token);
