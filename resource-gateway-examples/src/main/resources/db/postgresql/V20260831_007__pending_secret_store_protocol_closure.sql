-- Forward-only J3-B1e pending-secret protocol closure.
-- V20260830_006__pending_secret_store_hardening.sql must already be installed.
-- Keep provider_lease_until as the provider receipt; only the effective lease is
-- clamped to the earlier provider or command-journal deadline.

UPDATE rg_api_connection_pending_secret_leases p
   SET lease_until = LEAST(
           COALESCE(p.provider_lease_until, p.lease_until),
           (SELECT j.lease_until
              FROM rg_authoring_command_journal j
             WHERE j.command_id = p.command_id
               AND j.attempt_no = p.attempt_no
               AND j.attempt_token = p.attempt_token))
 WHERE EXISTS (
           SELECT 1
             FROM rg_authoring_command_journal j
            WHERE j.command_id = p.command_id
              AND j.attempt_no = p.attempt_no
              AND j.attempt_token = p.attempt_token
       );

-- V006 allowed a nullable mode while it backfilled old rows.  At this point a
-- row without a provable child CAS is not safe to operate and must fail the
-- migration rather than being silently repaired.
ALTER TABLE rg_api_connection_pending_secret_leases
    DROP CONSTRAINT IF EXISTS rg_api_connection_pending_secret_leases_child_expected_ck;
ALTER TABLE rg_api_connection_pending_secret_leases
    ALTER COLUMN child_expected_mode SET NOT NULL;
ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_child_expected_ck CHECK
        ((child_expected_mode = 'CREATE' AND child_expected_revision IS NULL AND revision = 1)
         OR (child_expected_mode = 'MATCH' AND child_expected_revision > 0
             AND child_expected_revision = revision - 1));

CREATE INDEX IF NOT EXISTS rg_api_connection_pending_secret_leases_journal_due_idx
    ON rg_api_connection_pending_secret_leases
       (command_id, attempt_no, attempt_token, status, lease_until, updated_at, slot);
