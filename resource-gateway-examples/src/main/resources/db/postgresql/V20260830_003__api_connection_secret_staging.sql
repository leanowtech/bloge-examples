-- Slice J3-B1a API Connection metadata, revision and lease staging schema.
-- Apply V20260830_001__api_resource_authoring.sql and V20260830_002__api_resource_concurrent_staging.sql first.
-- JSON columns contain only payload-free metadata; provider handles remain opaque.

CREATE TABLE IF NOT EXISTS rg_api_connection_identities (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_connection_identities_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, connection_id),
    CONSTRAINT rg_api_connection_identities_scope_ck CHECK
        (CHAR_LENGTH(TRIM(tenant_id)) > 0 AND CHAR_LENGTH(TRIM(project_id)) > 0
         AND CHAR_LENGTH(TRIM(environment_id)) > 0 AND CHAR_LENGTH(TRIM(connection_id)) > 0)
);

INSERT INTO rg_api_connection_identities
    (tenant_id, project_id, environment_id, connection_id)
SELECT DISTINCT r.tenant_id, r.project_id, r.environment_id, r.connection_id
  FROM rg_api_resource_revisions r
 WHERE NOT EXISTS (
       SELECT 1
         FROM rg_api_connection_identities i
        WHERE i.tenant_id = r.tenant_id
          AND i.project_id = r.project_id
          AND i.environment_id = r.environment_id
          AND i.connection_id = r.connection_id
   );

ALTER TABLE rg_api_resource_revisions
    ADD CONSTRAINT rg_api_resource_revisions_connection_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id)
        REFERENCES rg_api_connection_identities
            (tenant_id, project_id, environment_id, connection_id)
        ON DELETE RESTRICT;

CREATE TABLE IF NOT EXISTS rg_api_connection_revisions (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    view_json TEXT NOT NULL,
    metadata_fingerprint VARCHAR(128) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    defaults_headers_json TEXT NOT NULL,
    timeout_ms INTEGER NOT NULL,
    auth_kind VARCHAR(16) NOT NULL,
    basic_username VARCHAR(256),
    api_key_header VARCHAR(256),
    strong_etag VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_connection_revisions_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id),
    CONSTRAINT rg_api_connection_revisions_command_uq UNIQUE (command_id),
    CONSTRAINT rg_api_connection_revisions_revision_attempt_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_connection_revisions_identity_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id)
        REFERENCES rg_api_connection_identities
            (tenant_id, project_id, environment_id, connection_id),
    CONSTRAINT rg_api_connection_revisions_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_journal (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_connection_revisions_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag),
    CONSTRAINT rg_api_connection_revisions_state_etag_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag, state),
    CONSTRAINT rg_api_connection_revisions_revision_state_uq UNIQUE
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, state),
    CONSTRAINT rg_api_connection_revisions_state_ck CHECK (state IN ('STAGED', 'COMMITTED')),
    CONSTRAINT rg_api_connection_revisions_revision_ck CHECK (revision > 0),
    CONSTRAINT rg_api_connection_revisions_attempt_ck CHECK (attempt_no > 0),
    CONSTRAINT rg_api_connection_revisions_fingerprint_ck CHECK
        (CHAR_LENGTH(metadata_fingerprint) = 71 AND metadata_fingerprint LIKE 'sha256:%'
         AND LOWER(metadata_fingerprint) = metadata_fingerprint
         AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
             SUBSTRING(metadata_fingerprint, 8, 64), '0', ''), '1', ''), '2', ''), '3', ''),
             '4', ''), '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), 'a', ''),
             'b', ''), 'c', ''), 'd', ''), 'e', ''), 'f', '') = ''),
    CONSTRAINT rg_api_connection_revisions_url_ck CHECK
        (CHAR_LENGTH(base_url) BETWEEN 8 AND 2048 AND base_url = TRIM(base_url)
         AND base_url LIKE 'https://%' AND CHAR_LENGTH(base_url) > 8
         AND base_url NOT LIKE 'https:///%' AND base_url NOT LIKE 'https://:%'
         AND base_url NOT LIKE '% %' AND POSITION(CHR(9) IN base_url) = 0
         AND POSITION(CHR(10) IN base_url) = 0 AND POSITION(CHR(11) IN base_url) = 0
         AND POSITION(CHR(12) IN base_url) = 0 AND POSITION(CHR(13) IN base_url) = 0
         AND POSITION('@' IN SUBSTRING(base_url, 9,
             CASE WHEN POSITION('/' IN SUBSTRING(base_url, 9)) > 0
                  THEN POSITION('/' IN SUBSTRING(base_url, 9)) - 1 ELSE 2048 END)) = 0
         AND base_url NOT LIKE '%?%' AND base_url NOT LIKE '%#%'),
    CONSTRAINT rg_api_connection_revisions_timeout_ck CHECK (timeout_ms BETWEEN 100 AND 120000),
    CONSTRAINT rg_api_connection_revisions_auth_ck CHECK
        ((auth_kind = 'NONE' AND basic_username IS NULL AND api_key_header IS NULL)
         OR (auth_kind = 'BEARER' AND basic_username IS NULL AND api_key_header IS NULL)
         OR (auth_kind = 'BASIC' AND basic_username IS NOT NULL AND CHAR_LENGTH(TRIM(basic_username)) > 0
             AND api_key_header IS NULL)
         OR (auth_kind = 'API_KEY' AND basic_username IS NULL AND api_key_header IS NOT NULL
             AND CHAR_LENGTH(TRIM(api_key_header)) > 0)),
    CONSTRAINT rg_api_connection_revisions_etag_ck CHECK
        (CHAR_LENGTH(strong_etag) >= 3 AND strong_etag LIKE '"%"' AND strong_etag NOT LIKE '"W/%')
);

CREATE INDEX IF NOT EXISTS rg_api_connection_revisions_visibility_idx
    ON rg_api_connection_revisions
       (tenant_id, project_id, environment_id, connection_id, state, revision);
CREATE INDEX IF NOT EXISTS rg_api_connection_revisions_staging_cleanup_idx
    ON rg_api_connection_revisions (state, updated_at);

CREATE TABLE IF NOT EXISTS rg_api_connection_heads (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    strong_etag VARCHAR(256) NOT NULL,
    revision_state VARCHAR(16) NOT NULL DEFAULT 'COMMITTED',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_connection_heads_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, connection_id),
    CONSTRAINT rg_api_connection_heads_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag,
         revision_state)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag, state),
    CONSTRAINT rg_api_connection_heads_state_ck CHECK (revision_state = 'COMMITTED'),
    CONSTRAINT rg_api_connection_heads_revision_ck CHECK (revision > 0)
);

CREATE TABLE IF NOT EXISTS rg_api_connection_pending_secret_leases (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_token VARCHAR(128) NOT NULL,
    slot VARCHAR(32) NOT NULL,
    source_mode VARCHAR(32) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    lease_id VARCHAR(256) NOT NULL,
    opaque_handle VARCHAR(2048) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_connection_pending_secret_leases_pk PRIMARY KEY
        (command_id, attempt_no, attempt_token, slot),
    CONSTRAINT rg_api_connection_pending_secret_leases_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no, attempt_token)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_connection_pending_secret_leases_command_fk FOREIGN KEY
        (command_id, attempt_no, attempt_token)
        REFERENCES rg_authoring_command_journal (command_id, attempt_no, attempt_token),
    CONSTRAINT rg_api_connection_pending_secret_leases_slot_ck CHECK (slot IN ('token', 'password', 'value')),
    CONSTRAINT rg_api_connection_pending_secret_leases_source_ck CHECK
        (source_mode IN ('VALUE', 'SECRET_REF', 'KEEP_EXISTING')),
    CONSTRAINT rg_api_connection_pending_secret_leases_status_ck CHECK
        (status IN ('PENDING', 'ABORT_REQUIRED')),
    CONSTRAINT rg_api_connection_pending_secret_leases_attempt_ck CHECK (attempt_no > 0),
    CONSTRAINT rg_api_connection_pending_secret_leases_revision_ck CHECK (revision > 0)
);

CREATE INDEX IF NOT EXISTS rg_api_connection_pending_secret_leases_recovery_idx
    ON rg_api_connection_pending_secret_leases
       (status, lease_until, updated_at, command_id, attempt_no, attempt_token, slot);

CREATE TABLE IF NOT EXISTS rg_api_connection_secret_bindings (
    tenant_id VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    environment_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    revision_state VARCHAR(16) NOT NULL DEFAULT 'COMMITTED',
    slot VARCHAR(32) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    active_locator VARCHAR(2048) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rg_api_connection_secret_bindings_pk PRIMARY KEY
        (tenant_id, project_id, environment_id, connection_id, revision, slot),
    CONSTRAINT rg_api_connection_secret_bindings_revision_fk FOREIGN KEY
        (tenant_id, project_id, environment_id, connection_id, revision, command_id, revision_state)
        REFERENCES rg_api_connection_revisions
            (tenant_id, project_id, environment_id, connection_id, revision, command_id, state),
    CONSTRAINT rg_api_connection_secret_bindings_command_fk FOREIGN KEY
        (command_id) REFERENCES rg_authoring_command_journal (command_id),
    CONSTRAINT rg_api_connection_secret_bindings_state_ck CHECK (revision_state = 'COMMITTED'),
    CONSTRAINT rg_api_connection_secret_bindings_slot_ck CHECK (slot IN ('token', 'password', 'value'))
);

CREATE INDEX IF NOT EXISTS rg_api_connection_secret_bindings_locator_idx
    ON rg_api_connection_secret_bindings
       (tenant_id, project_id, environment_id, connection_id, slot, provider_id);
