package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** JDBC implementation storing only payload-free impact projections. */
public final class DatabaseWorldImpactSnapshotRepository implements WorldImpactSnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public DatabaseWorldImpactSnapshotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null) throw invalid();
        this.jdbc = jdbc;
        this.mapper = mapper.copy().registerModule(new JavaTimeModule());
        this.postgres = isPostgres(jdbc);
    }

    @Override
    public IndexedStatic upsertStatic(WorldStaticDependencySnapshot snapshot) {
        if (snapshot == null) throw invalid();
        String json = encode(snapshot);
        Optional<String> existing = fingerprint("rg_world_impact_static_snapshots",
                snapshot.tenantId(), snapshot.scenarioId(), snapshot.scenarioRevision());
        if (existing.isPresent() && !existing.get().equals(snapshot.fingerprint())) throw conflict();
        if (existing.isEmpty()) {
            try {
                jdbc.update("INSERT INTO rg_world_impact_static_snapshots"
                                + "(tenant_id,scenario_id,scenario_revision,snapshot_fingerprint,source_watermark,generated_at,canonical_json)"
                                + " VALUES (?,?,?,?,?,?,%s)".formatted(jsonParameter()), snapshot.tenantId(), snapshot.scenarioId(),
                        snapshot.scenarioRevision(), snapshot.fingerprint(), snapshot.sourceWatermark(),
                        Timestamp.from(snapshot.generatedAt()), json);
            } catch (DuplicateKeyException race) {
                String winner = fingerprint("rg_world_impact_static_snapshots", snapshot.tenantId(),
                        snapshot.scenarioId(), snapshot.scenarioRevision()).orElseThrow(DatabaseWorldImpactSnapshotRepository::invalid);
                if (!winner.equals(snapshot.fingerprint())) throw conflict();
            }
        }
        advance("static", snapshot.tenantId(), snapshot.sourceWatermark());
        return readStatic(snapshot.tenantId(), snapshot.scenarioId(), snapshot.scenarioRevision(), snapshot.fingerprint())
                .orElseThrow(DatabaseWorldImpactSnapshotRepository::invalid);
    }

    @Override
    public IndexedRuntime upsertRuntime(WorldRuntimeConsumptionSnapshot snapshot) {
        if (snapshot == null) throw invalid();
        String json = encode(snapshot);
        Optional<String> existing = jdbc.query("SELECT snapshot_fingerprint FROM rg_world_impact_runtime_snapshots"
                        + " WHERE tenant_id=? AND run_id=?", rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(),
                snapshot.tenantId(), snapshot.runId());
        if (existing.isPresent() && !existing.get().equals(snapshot.fingerprint())) throw conflict();
        if (existing.isEmpty()) {
            try {
                jdbc.update("INSERT INTO rg_world_impact_runtime_snapshots"
                                + "(tenant_id,run_id,scenario_id,scenario_revision,snapshot_fingerprint,source_watermark,generated_at,canonical_json)"
                                + " VALUES (?,?,?,?,?,?,?,%s)".formatted(jsonParameter()), snapshot.tenantId(), snapshot.runId(),
                        snapshot.scenarioId(), snapshot.scenarioRevision(), snapshot.fingerprint(), snapshot.sourceWatermark(),
                        Timestamp.from(snapshot.generatedAt()), json);
            } catch (DuplicateKeyException race) {
                String winner = jdbc.queryForObject("SELECT snapshot_fingerprint FROM rg_world_impact_runtime_snapshots"
                        + " WHERE tenant_id=? AND run_id=?", String.class, snapshot.tenantId(), snapshot.runId());
                if (!snapshot.fingerprint().equals(winner)) throw conflict();
            }
        }
        advance("runtime", snapshot.tenantId(), snapshot.sourceWatermark());
        return readRuntime(snapshot.tenantId(), snapshot.runId(), snapshot.fingerprint())
                .orElseThrow(DatabaseWorldImpactSnapshotRepository::invalid);
    }

    @Override
    public Optional<IndexedStatic> readStatic(String tenantId, String scenarioId, long revision, String fingerprint) {
        if (!valid(tenantId, scenarioId) || revision < 1 || !validFingerprint(fingerprint)) return Optional.empty();
        return jdbc.query("SELECT canonical_json FROM rg_world_impact_static_snapshots WHERE tenant_id=? AND scenario_id=?"
                        + " AND scenario_revision=? AND snapshot_fingerprint=?", rs -> rs.next()
                        ? Optional.of(index(verifyStaticRow(decodeStatic(rs.getString(1)), tenantId, scenarioId,
                        revision, fingerprint), staticWatermark(tenantId))) : Optional.empty(),
                tenantId, scenarioId, revision, fingerprint);
    }

    @Override
    public Optional<IndexedRuntime> readRuntime(String tenantId, String runId, String fingerprint) {
        if (!valid(tenantId, runId) || !validFingerprint(fingerprint)) return Optional.empty();
        return jdbc.query("SELECT canonical_json FROM rg_world_impact_runtime_snapshots WHERE tenant_id=? AND run_id=?"
                        + " AND snapshot_fingerprint=?", rs -> rs.next()
                        ? Optional.of(index(verifyRuntimeRow(decodeRuntime(rs.getString(1)), tenantId, runId,
                        fingerprint), runtimeWatermark(tenantId))) : Optional.empty(),
                tenantId, runId, fingerprint);
    }

    @Override
    public List<IndexedStatic> staticSnapshots(String tenantId) {
        if (!validText(tenantId)) return List.of();
        long watermark = staticWatermark(tenantId);
        return jdbc.query("SELECT canonical_json FROM rg_world_impact_static_snapshots WHERE tenant_id=?"
                        + " ORDER BY scenario_id,scenario_revision", (rs, row) -> index(decodeStatic(rs.getString(1)), watermark), tenantId);
    }

    @Override
    public List<IndexedRuntime> runtimeSnapshots(String tenantId) {
        if (!validText(tenantId)) return List.of();
        long watermark = runtimeWatermark(tenantId);
        return jdbc.query("SELECT canonical_json FROM rg_world_impact_runtime_snapshots WHERE tenant_id=?"
                        + " ORDER BY run_id", (rs, row) -> index(decodeRuntime(rs.getString(1)), watermark), tenantId);
    }

    @Override public long staticWatermark(String tenantId) { return watermark("static", tenantId); }
    @Override public long runtimeWatermark(String tenantId) { return watermark("runtime", tenantId); }

    private WorldImpactSnapshotRepository.IndexedStatic index(WorldStaticDependencySnapshot value, long watermark) {
        return new IndexedStatic(value, Math.max(1, watermark));
    }
    private WorldImpactSnapshotRepository.IndexedRuntime index(WorldRuntimeConsumptionSnapshot value, long watermark) {
        return new IndexedRuntime(value, Math.max(1, watermark));
    }

    private WorldStaticDependencySnapshot decodeStatic(String json) {
        try { return mapper.readValue(json, WorldStaticDependencySnapshot.class); }
        catch (Exception failure) { throw invalid(); }
    }
    private WorldRuntimeConsumptionSnapshot decodeRuntime(String json) {
        try { return mapper.readValue(json, WorldRuntimeConsumptionSnapshot.class); }
        catch (Exception failure) { throw invalid(); }
    }

    private WorldStaticDependencySnapshot verifyStaticRow(WorldStaticDependencySnapshot snapshot,
                                                          String tenant, String scenario, long revision,
                                                          String fingerprint) {
        if (!tenant.equals(snapshot.tenantId()) || !scenario.equals(snapshot.scenarioId())
                || revision != snapshot.scenarioRevision() || !fingerprint.equals(snapshot.fingerprint())) {
            throw invalid();
        }
        return snapshot;
    }

    private WorldRuntimeConsumptionSnapshot verifyRuntimeRow(WorldRuntimeConsumptionSnapshot snapshot,
                                                             String tenant, String runId, String fingerprint) {
        if (!tenant.equals(snapshot.tenantId()) || !runId.equals(snapshot.runId())
                || !fingerprint.equals(snapshot.fingerprint())) {
            throw invalid();
        }
        return snapshot;
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw invalid(); }
    }

    private Optional<String> fingerprint(String table, String tenant, String scenario, long revision) {
        return jdbc.query("SELECT snapshot_fingerprint FROM " + table
                        + " WHERE tenant_id=? AND scenario_id=? AND scenario_revision=?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), tenant, scenario, revision);
    }

    private void advance(String kind, String tenant, long value) {
        int updated = jdbc.update("UPDATE rg_world_impact_watermarks SET watermark=CASE WHEN watermark<? THEN ? ELSE watermark END"
                + " WHERE tenant_id=? AND index_kind=?", value, value, tenant, kind);
        if (updated == 0) {
            try { jdbc.update("INSERT INTO rg_world_impact_watermarks(tenant_id,index_kind,watermark) VALUES (?,?,?)",
                    tenant, kind, value); }
            catch (DuplicateKeyException race) { jdbc.update("UPDATE rg_world_impact_watermarks SET watermark=CASE WHEN watermark<? THEN ? ELSE watermark END"
                    + " WHERE tenant_id=? AND index_kind=?", value, value, tenant, kind); }
        }
    }

    private long watermark(String kind, String tenant) {
        if (!validText(tenant)) return 0;
        Long value = jdbc.query("SELECT watermark FROM rg_world_impact_watermarks WHERE tenant_id=? AND index_kind=?",
                rs -> rs.next() ? rs.getLong(1) : null, tenant, kind);
        return value == null ? 0 : value;
    }
    private String jsonParameter() { return postgres ? "CAST(? AS JSONB)" : "?"; }
    private static boolean isPostgres(JdbcTemplate jdbc) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (Exception failure) { throw invalid(); }
    }
    private static boolean valid(String tenant, String id) { return validText(tenant) && validText(id); }
    private static boolean validText(String value) { return value != null && !value.isBlank() && value.length() <= WorldImpactSupport.MAX_TEXT
            && value.chars().noneMatch(Character::isISOControl); }
    private static boolean validFingerprint(String value) { return value != null && WorldImpactSupport.FINGERPRINT.matcher(value).matches(); }
    private static WorldImpactException conflict() { return WorldImpactSupport.fail(WorldImpactException.Code.INDEX_CONFLICT); }
    private static WorldImpactException invalid() { return WorldImpactSupport.fail(WorldImpactException.Code.SOURCE_INTEGRITY); }
}
