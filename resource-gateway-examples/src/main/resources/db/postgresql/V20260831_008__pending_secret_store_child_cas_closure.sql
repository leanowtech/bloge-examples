-- Forward-only closure for the child CAS invariant.
-- V007 deliberately preserved legacy history and made child_expected_mode non-null;
-- this migration replaces its three-valued CHECK with an explicit boolean closure.
ALTER TABLE rg_api_connection_pending_secret_leases
    DROP CONSTRAINT IF EXISTS rg_api_connection_pending_secret_leases_child_expected_ck;

ALTER TABLE rg_api_connection_pending_secret_leases
    ADD CONSTRAINT rg_api_connection_pending_secret_leases_child_expected_exact_ck CHECK
        (COALESCE(
            ((child_expected_mode = 'CREATE'
              AND child_expected_revision IS NULL
              AND revision = 1)
             OR
             (child_expected_mode = 'MATCH'
              AND child_expected_revision IS NOT NULL
              AND child_expected_revision > 0
              AND child_expected_revision = revision - 1)),
            FALSE));
