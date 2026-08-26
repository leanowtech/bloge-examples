-- S2-E2a: allow the governed function-control asset kind in existing catalog tables.
-- The explicit constraint names are stable so upgrades work for databases created by V001.

ALTER TABLE rg_world_catalog_heads
    DROP CONSTRAINT IF EXISTS rg_world_catalog_heads_kind_check;
ALTER TABLE rg_world_catalog_heads
    ADD CONSTRAINT rg_world_catalog_heads_kind_check
    CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO', 'FUNCTION_CONTROL'));

ALTER TABLE rg_world_catalog_revisions
    DROP CONSTRAINT IF EXISTS rg_world_catalog_revisions_kind_check;
ALTER TABLE rg_world_catalog_revisions
    ADD CONSTRAINT rg_world_catalog_revisions_kind_check
    CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO', 'FUNCTION_CONTROL'));
