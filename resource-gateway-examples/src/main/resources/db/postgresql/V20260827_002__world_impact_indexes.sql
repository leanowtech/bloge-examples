-- Immutable, payload-free S3-B static and runtime impact indexes.
CREATE TABLE IF NOT EXISTS rg_world_impact_static_snapshots (
    tenant_id TEXT NOT NULL,
    scenario_id TEXT NOT NULL,
    scenario_revision BIGINT NOT NULL CHECK (scenario_revision > 0),
    snapshot_fingerprint TEXT NOT NULL CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    source_watermark BIGINT NOT NULL CHECK (source_watermark > 0),
    generated_at TIMESTAMPTZ NOT NULL,
    canonical_json JSONB NOT NULL,
    PRIMARY KEY (tenant_id, scenario_id, scenario_revision)
);
CREATE UNIQUE INDEX IF NOT EXISTS rg_world_impact_static_fingerprint_idx
    ON rg_world_impact_static_snapshots (tenant_id, snapshot_fingerprint);

CREATE TABLE IF NOT EXISTS rg_world_impact_runtime_snapshots (
    tenant_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    scenario_id TEXT NOT NULL,
    scenario_revision BIGINT NOT NULL CHECK (scenario_revision > 0),
    snapshot_fingerprint TEXT NOT NULL CHECK (snapshot_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
    source_watermark BIGINT NOT NULL CHECK (source_watermark > 0),
    generated_at TIMESTAMPTZ NOT NULL,
    canonical_json JSONB NOT NULL,
    PRIMARY KEY (tenant_id, run_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS rg_world_impact_runtime_fingerprint_idx
    ON rg_world_impact_runtime_snapshots (tenant_id, snapshot_fingerprint);

CREATE TABLE IF NOT EXISTS rg_world_impact_watermarks (
    tenant_id TEXT NOT NULL,
    index_kind TEXT NOT NULL CHECK (index_kind IN ('static', 'runtime')),
    watermark BIGINT NOT NULL CHECK (watermark > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, index_kind)
);
CREATE INDEX IF NOT EXISTS rg_world_impact_static_watermark_idx
    ON rg_world_impact_static_snapshots (tenant_id, source_watermark);
CREATE INDEX IF NOT EXISTS rg_world_impact_runtime_watermark_idx
    ON rg_world_impact_runtime_snapshots (tenant_id, source_watermark);
