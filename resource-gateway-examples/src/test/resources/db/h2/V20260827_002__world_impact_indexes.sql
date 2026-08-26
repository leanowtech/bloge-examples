-- Test-only H2 schema; production uses the PostgreSQL migration with JSONB.
CREATE TABLE rg_world_impact_static_snapshots (
    tenant_id VARCHAR(512) NOT NULL,
    scenario_id VARCHAR(512) NOT NULL,
    scenario_revision BIGINT NOT NULL CHECK (scenario_revision > 0),
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    source_watermark BIGINT NOT NULL CHECK (source_watermark > 0),
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id, scenario_id, scenario_revision),
    UNIQUE (tenant_id, snapshot_fingerprint)
);
CREATE INDEX rg_world_impact_static_watermark_idx
    ON rg_world_impact_static_snapshots (tenant_id, source_watermark);

CREATE TABLE rg_world_impact_runtime_snapshots (
    tenant_id VARCHAR(512) NOT NULL,
    run_id VARCHAR(512) NOT NULL,
    scenario_id VARCHAR(512) NOT NULL,
    scenario_revision BIGINT NOT NULL CHECK (scenario_revision > 0),
    snapshot_fingerprint VARCHAR(80) NOT NULL,
    source_watermark BIGINT NOT NULL CHECK (source_watermark > 0),
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    canonical_json TEXT NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    UNIQUE (tenant_id, snapshot_fingerprint)
);
CREATE INDEX rg_world_impact_runtime_watermark_idx
    ON rg_world_impact_runtime_snapshots (tenant_id, source_watermark);

CREATE TABLE rg_world_impact_watermarks (
    tenant_id VARCHAR(512) NOT NULL,
    index_kind VARCHAR(16) NOT NULL CHECK (index_kind IN ('static', 'runtime')),
    watermark BIGINT NOT NULL CHECK (watermark > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, index_kind)
);
