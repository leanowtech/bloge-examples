-- Forward-only command-attempt authority.
-- V20260831_008__pending_secret_store_child_cas_closure.sql and all earlier
-- authoring migrations must already be installed.  The mutable journal keeps
-- only the current pointer; this table keeps every lease authority immutable.

CREATE TABLE IF NOT EXISTS rg_authoring_command_attempts (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    endpoint VARCHAR(128) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    expected_mode VARCHAR(16) NOT NULL,
    expected_revision BIGINT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_authoring_command_attempts_pk PRIMARY KEY (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_authoring_command_attempts_no_uq UNIQUE (command_id, attempt_no),
    CONSTRAINT rg_authoring_command_attempts_token_uq UNIQUE (command_id, attempt_token),
    CONSTRAINT rg_authoring_command_attempts_status_ck CHECK
        (status IN ('PREPARING', 'COMMITTED', 'FAILED')),
    CONSTRAINT rg_authoring_command_attempts_attempt_ck CHECK (attempt_no > 0),
    CONSTRAINT rg_authoring_command_attempts_expected_ck CHECK
        ((expected_mode = 'CREATE' AND expected_revision IS NULL)
         OR (expected_mode = 'MATCH' AND expected_revision > 0)),
    CONSTRAINT rg_authoring_command_attempts_fingerprint_ck CHECK
        (CHAR_LENGTH(request_fingerprint) = 71 AND request_fingerprint LIKE 'sha256:%'
         AND LOWER(request_fingerprint) = request_fingerprint
         AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             SUBSTRING(request_fingerprint, 8, 64), '0', ''), '1', ''), '2', ''), '3', ''),
             '4', ''), '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), 'a', ''),
             'b', ''), 'c', ''), 'd', ''), 'e', ''), 'f', '') = '')
);

-- A strict INSERT is intentional: a legacy journal row that cannot prove a
-- complete CommandLease authority aborts this migration instead of being
-- silently made recoverable with invented values.
INSERT INTO rg_authoring_command_attempts
    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
     idempotency_key, command_id, request_fingerprint, status, attempt_no,
     attempt_token, lease_until, expected_mode, expected_revision, created_at, updated_at)
SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
       idempotency_key, command_id, request_fingerprint, status, attempt_no,
       attempt_token, lease_until, expected_mode, expected_revision, created_at, updated_at
  FROM rg_authoring_command_journal;

ALTER TABLE rg_api_resource_revisions
    DROP CONSTRAINT rg_api_resource_revisions_command_fk;
ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_attempts (command_id, attempt_no, attempt_token);

ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_command_fk;
ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_attempts (command_id, attempt_no, attempt_token);

ALTER TABLE rg_api_connection_pending_secret_leases
    DROP CONSTRAINT rg_api_connection_pending_secret_leases_command_fk;
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_attempts (command_id, attempt_no, attempt_token);

ALTER TABLE rg_api_connection_pending_secret_outcomes
    DROP CONSTRAINT rg_api_connection_pending_secret_outcomes_command_fk;
ALTER TABLE rg_api_connection_pending_secret_outcomes
    ADD CONSTRAINT rg_api_connection_pending_secret_outcomes_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_attempts (command_id, attempt_no, attempt_token);

-- A takeover can leave a pending child revision tied to the expired attempt.
-- Keep that provenance and permit the replacement attempt to stage the same
-- logical revision by making the immutable attempt part of the child key. The
-- head/binding references intentionally retain their compact committed-row
-- key, backed by the unique committed-row identity below.
ALTER TABLE rg_api_connection_pending_secret_leases
    DROP CONSTRAINT rg_api_connection_pending_secret_leases_revision_fk;
ALTER TABLE rg_api_connection_heads
    DROP CONSTRAINT rg_api_connection_heads_revision_fk;
ALTER TABLE rg_api_connection_secret_bindings
    DROP CONSTRAINT rg_api_connection_secret_bindings_revision_fk;

ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_pk;
ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_command_uq;
ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_revision_attempt_uq;
ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_etag_uq;
ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_state_etag_uq;
ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_revision_state_uq;

ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         attempt_no, attempt_token);
ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_committed_ref_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         state);
ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_state_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         strong_etag, state);

ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id,
         attempt_no, attempt_token)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id,
             attempt_no, attempt_token);
ALTER TABLE rg_api_connection_heads
    ADD CONSTRAINT rg_api_connection_heads_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag,
         revision_state)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id,
             strong_etag, state);
ALTER TABLE rg_api_connection_secret_bindings
    ADD CONSTRAINT rg_api_connection_secret_bindings_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, revision_state)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id, state);

CREATE INDEX IF NOT EXISTS rg_authoring_command_attempts_recovery_idx
    ON rg_authoring_command_attempts (status, lease_until, command_id, attempt_no, attempt_token);
