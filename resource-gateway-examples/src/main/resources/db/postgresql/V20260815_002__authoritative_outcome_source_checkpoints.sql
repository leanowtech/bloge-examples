-- Production authoritative Outcome source cursor, staged-page, and control-command authority.
-- Apply before enabling gateway.testing.mirror.outcome-source.scheduler.enabled.

CREATE TABLE IF NOT EXISTS mirror_outcome_source_checkpoint_locks (
    region VARCHAR(96) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (region, environment_id)
);

CREATE TABLE IF NOT EXISTS mirror_outcome_source_commands (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region VARCHAR(96) NOT NULL,
    command_id VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    command_fingerprint VARCHAR(71) NOT NULL,
    connector_id VARCHAR(512) NOT NULL,
    connector_generation BIGINT NOT NULL CHECK (connector_generation > 0),
    command_type VARCHAR(32) NOT NULL CHECK (
        command_type IN ('BACKFILL', 'REVOKE_GENERATION')
    ),
    affected_stream_count INTEGER NOT NULL CHECK (affected_stream_count >= 0),
    command_json TEXT NOT NULL,
    admitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region,
        command_id, revision
    ),
    CHECK (command_fingerprint ~ '^sha256:[a-f0-9]{64}$')
);

CREATE TABLE IF NOT EXISTS mirror_outcome_source_checkpoints (
    tenant_id VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    environment_id VARCHAR(255) NOT NULL,
    region VARCHAR(96) NOT NULL,
    connector_id VARCHAR(512) NOT NULL,
    connector_generation BIGINT NOT NULL CHECK (connector_generation > 0),
    stream_kind VARCHAR(32) NOT NULL CHECK (stream_kind IN ('LIVE', 'BACKFILL')),
    stream_id VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('ACTIVE', 'RUNNING', 'COMPLETE', 'REVOKED', 'QUARANTINED')
    ),
    committed_sequence BIGINT NOT NULL CHECK (committed_sequence >= 0),
    committed_page_fingerprint VARCHAR(71) NOT NULL,
    committed_cursor_fingerprint VARCHAR(71) NOT NULL,
    event_time_through TIMESTAMP WITH TIME ZONE NOT NULL,
    staged_page_fingerprint VARCHAR(71) NOT NULL,
    staged_page_json TEXT NOT NULL,
    attempt_count BIGINT NOT NULL CHECK (attempt_count >= 0),
    consecutive_failures INTEGER NOT NULL CHECK (consecutive_failures >= 0),
    next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_owner VARCHAR(512) NOT NULL,
    lease_epoch BIGINT NOT NULL CHECK (lease_epoch >= 0),
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    failure_code VARCHAR(255) NOT NULL,
    record_fingerprint VARCHAR(71) NOT NULL,
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY (
        tenant_id, organization_id, project_id, environment_id, region,
        connector_id, connector_generation, stream_kind, stream_id
    ),
    CHECK (committed_page_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (committed_cursor_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK (staged_page_fingerprint = '' OR
           staged_page_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    CHECK ((staged_page_fingerprint = '') = (staged_page_json = '')),
    CHECK (stream_kind <> 'LIVE' OR stream_id = 'live'),
    CHECK (status NOT IN ('COMPLETE', 'REVOKED') OR staged_page_fingerprint = '')
);

CREATE INDEX IF NOT EXISTS idx_mirror_outcome_source_schedule
    ON mirror_outcome_source_checkpoints (
        region, environment_id, status, next_eligible_at, lease_expires_at,
        connector_id, connector_generation, stream_kind, stream_id
    );
