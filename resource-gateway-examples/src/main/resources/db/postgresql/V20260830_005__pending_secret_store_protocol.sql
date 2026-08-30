-- Additive J3-B1c pending-secret protocol closure.
-- V20260830_003 and V20260830_004 must already be installed.
-- Provider values and locators remain opaque; source columns are metadata only.

ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN source_tenant_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN source_project_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN source_environment_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN source_connection_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN source_revision BIGINT;

CREATE TABLE IF NOT EXISTS rg_api_connection_pending_secret_outcomes (
    command_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    outcome_fingerprint VARCHAR(128) NOT NULL,
    slots_csv VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_connection_pending_secret_outcomes_pk
        PRIMARY KEY (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_connection_pending_secret_outcomes_command_fk
        FOREIGN KEY (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_journal (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_connection_pending_secret_outcomes_outcome_ck
        CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    CONSTRAINT rg_api_connection_pending_secret_outcomes_fingerprint_ck
        CHECK (CHAR_LENGTH(outcome_fingerprint) = 71 AND outcome_fingerprint LIKE 'sha256:%'),
    CONSTRAINT rg_api_connection_pending_secret_outcomes_slots_ck
        CHECK (CHAR_LENGTH(slots_csv) > 0)
);

CREATE INDEX IF NOT EXISTS rg_api_connection_pending_secret_outcomes_command_idx
    ON rg_api_connection_pending_secret_outcomes (command_id, outcome);
