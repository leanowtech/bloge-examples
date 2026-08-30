-- Additive J3-B1d hardening for durable pending-secret recovery and replay.
-- V20260830_005__pending_secret_store_protocol.sql must already be installed.
-- Provider values remain opaque locators; recovery values are opaque fences.

ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN child_expected_mode VARCHAR(16);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN child_expected_revision BIGINT;
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN recovery_claim_owner VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN recovery_claim_token VARCHAR(256);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN recovery_claim_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_tenant_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_project_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_environment_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_actor_id VARCHAR(256);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_purpose VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_connection_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_revision BIGINT;
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_command_id VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_attempt_no INTEGER;
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN context_attempt_token VARCHAR(128);
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD COLUMN provider_lease_until TIMESTAMP WITH TIME ZONE;

-- Backfill only from existing durable columns/journal authority so this
-- forward migration remains deployable with already-staged V005 rows.
UPDATE rg_api_connection_pending_secret_leases
   SET child_expected_mode = CASE WHEN revision = 1 THEN 'CREATE' ELSE 'MATCH' END,
       child_expected_revision = CASE WHEN revision = 1 THEN NULL ELSE revision - 1 END,
       provider_lease_until = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE lease_until END,
       context_tenant_id = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE tenant_id END,
       context_project_id = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE project_id END,
       context_environment_id = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE environment_id END,
       context_purpose = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE 'connection-save' END,
       context_connection_id = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE connection_id END,
       context_revision = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE revision END,
       context_command_id = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE command_id END,
       context_attempt_no = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE attempt_no END,
       context_attempt_token = CASE WHEN source_mode = 'KEEP_EXISTING' THEN NULL ELSE attempt_token END;
UPDATE rg_api_connection_pending_secret_leases p
   SET context_actor_id = (SELECT j.actor_id FROM rg_authoring_command_journal j
                            WHERE j.command_id = p.command_id)
 WHERE p.source_mode <> 'KEEP_EXISTING';

ALTER TABLE rg_api_connection_pending_secret_leases
    ALTER COLUMN provider_id DROP NOT NULL;
ALTER TABLE rg_api_connection_pending_secret_leases
    ALTER COLUMN lease_id DROP NOT NULL;
ALTER TABLE rg_api_connection_pending_secret_leases
    ALTER COLUMN opaque_handle DROP NOT NULL;

UPDATE rg_api_connection_pending_secret_leases
   SET provider_id = NULL, lease_id = NULL, opaque_handle = NULL
 WHERE source_mode = 'KEEP_EXISTING' AND provider_id = '__retained__';

ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_child_expected_ck CHECK
        ((child_expected_mode = 'CREATE' AND child_expected_revision IS NULL)
         OR (child_expected_mode = 'MATCH' AND child_expected_revision > 0));
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_source_complete_ck CHECK
        ((source_mode = 'KEEP_EXISTING'
          AND source_tenant_id IS NOT NULL AND source_project_id IS NOT NULL
          AND source_environment_id IS NOT NULL AND source_connection_id IS NOT NULL
          AND source_revision > 0)
         OR (source_mode <> 'KEEP_EXISTING'
          AND source_tenant_id IS NULL AND source_project_id IS NULL
          AND source_environment_id IS NULL AND source_connection_id IS NULL
          AND source_revision IS NULL));
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_provider_complete_ck CHECK
        ((source_mode = 'KEEP_EXISTING' AND provider_id IS NULL AND lease_id IS NULL AND opaque_handle IS NULL
          AND provider_lease_until IS NULL)
         OR (source_mode <> 'KEEP_EXISTING'
          AND provider_id IS NOT NULL AND lease_id IS NOT NULL AND opaque_handle IS NOT NULL
          AND provider_lease_until IS NOT NULL));
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_claim_complete_ck CHECK
        ((recovery_claim_owner IS NULL AND recovery_claim_token IS NULL AND recovery_claim_until IS NULL)
         OR (recovery_claim_owner IS NOT NULL AND recovery_claim_token IS NOT NULL AND recovery_claim_until IS NOT NULL));
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_context_complete_ck CHECK
        ((source_mode = 'KEEP_EXISTING'
          AND context_tenant_id IS NULL AND context_project_id IS NULL
          AND context_environment_id IS NULL AND context_actor_id IS NULL
          AND context_purpose IS NULL AND context_connection_id IS NULL
          AND context_revision IS NULL AND context_command_id IS NULL
          AND context_attempt_no IS NULL AND context_attempt_token IS NULL)
         OR (source_mode <> 'KEEP_EXISTING'
          AND context_tenant_id IS NOT NULL AND context_project_id IS NOT NULL
          AND context_environment_id IS NOT NULL AND context_actor_id IS NOT NULL
          AND context_purpose IS NOT NULL AND context_connection_id IS NOT NULL
          AND context_revision > 0 AND context_command_id IS NOT NULL
          AND context_attempt_no > 0 AND context_attempt_token IS NOT NULL));

ALTER TABLE rg_api_connection_pending_secret_outcomes
    ADD COLUMN recovery_claim_token VARCHAR(256);
ALTER TABLE rg_api_connection_pending_secret_outcomes
    DROP CONSTRAINT rg_api_connection_pending_secret_outcomes_fingerprint_ck;
ALTER TABLE rg_api_connection_pending_secret_outcomes
    ADD CONSTRAINT rg_api_connection_pending_secret_outcomes_fingerprint_ck CHECK
        (CHAR_LENGTH(outcome_fingerprint) = 71 AND outcome_fingerprint LIKE 'sha256:%'
         AND LOWER(outcome_fingerprint) = outcome_fingerprint
         AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             SUBSTRING(outcome_fingerprint, 8, 64), '0', ''), '1', ''), '2', ''), '3', ''),
             '4', ''), '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), 'a', ''),
             'b', ''), 'c', ''), 'd', ''), 'e', ''), 'f', '') = '');

CREATE INDEX IF NOT EXISTS rg_api_connection_pending_secret_claim_idx
    ON rg_api_connection_pending_secret_leases
       (status, recovery_claim_until, lease_until, updated_at, command_id, attempt_no, attempt_token, slot);
