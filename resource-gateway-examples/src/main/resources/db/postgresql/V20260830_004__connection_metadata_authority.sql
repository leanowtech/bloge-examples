-- Slice J3-B1b: additive connection metadata and auth-slot authority.
-- V20260830_003 deliberately contains only the original staging contract.
--
-- Deployment precondition: rg_api_connection_revisions must be empty.  The
-- display name has no safe cross-engine value to derive from an existing
-- revision, so this migration fails rather than inventing or silently
-- changing persisted metadata when adding its NOT NULL column.

ALTER TABLE rg_api_connection_revisions
    ADD COLUMN display_name VARCHAR(200) NOT NULL;

ALTER TABLE rg_api_connection_revisions
    ADD COLUMN secret_slot VARCHAR(32);

ALTER TABLE rg_api_connection_revisions
    DROP CONSTRAINT rg_api_connection_revisions_auth_ck;

ALTER TABLE rg_api_connection_revisions
    ADD CONSTRAINT rg_api_connection_revisions_auth_ck CHECK
        ((auth_kind = 'NONE' AND basic_username IS NULL AND api_key_header IS NULL AND secret_slot IS NULL)
         OR (auth_kind = 'BEARER' AND basic_username IS NULL AND api_key_header IS NULL
             AND secret_slot IS NOT NULL AND secret_slot = 'token')
         OR (auth_kind = 'BASIC' AND basic_username IS NOT NULL AND CHAR_LENGTH(TRIM(basic_username)) > 0
             AND api_key_header IS NULL AND secret_slot IS NOT NULL AND secret_slot = 'password')
         OR (auth_kind = 'API_KEY' AND basic_username IS NULL AND api_key_header IS NOT NULL
             AND CHAR_LENGTH(TRIM(api_key_header)) > 0 AND secret_slot IS NOT NULL AND secret_slot = 'value'));
