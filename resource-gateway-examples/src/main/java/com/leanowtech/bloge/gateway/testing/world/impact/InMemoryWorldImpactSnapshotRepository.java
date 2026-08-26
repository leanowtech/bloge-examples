package com.leanowtech.bloge.gateway.testing.world.impact;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Restart-independent test adapter with immutable revision and explicit watermark semantics. */
public final class InMemoryWorldImpactSnapshotRepository implements WorldImpactSnapshotRepository {
    private final Map<StaticKey, WorldStaticDependencySnapshot> statics = new HashMap<>();
    private final Map<RuntimeKey, WorldRuntimeConsumptionSnapshot> runtimes = new HashMap<>();
    private final Map<String, Long> staticWatermarks = new HashMap<>();
    private final Map<String, Long> runtimeWatermarks = new HashMap<>();

    @Override
    public synchronized IndexedStatic upsertStatic(WorldStaticDependencySnapshot snapshot) {
        require(snapshot);
        StaticKey key = new StaticKey(snapshot.tenantId(), snapshot.scenarioId(), snapshot.scenarioRevision());
        WorldStaticDependencySnapshot previous = statics.get(key);
        if (previous != null && !previous.fingerprint().equals(snapshot.fingerprint())) throw conflict();
        statics.putIfAbsent(key, snapshot);
        staticWatermarks.merge(snapshot.tenantId(), snapshot.sourceWatermark(), Math::max);
        return new IndexedStatic(statics.get(key), staticWatermarks.get(snapshot.tenantId()));
    }

    @Override
    public synchronized IndexedRuntime upsertRuntime(WorldRuntimeConsumptionSnapshot snapshot) {
        require(snapshot);
        RuntimeKey key = new RuntimeKey(snapshot.tenantId(), snapshot.runId());
        WorldRuntimeConsumptionSnapshot previous = runtimes.get(key);
        if (previous != null && !previous.fingerprint().equals(snapshot.fingerprint())) throw conflict();
        runtimes.putIfAbsent(key, snapshot);
        runtimeWatermarks.merge(snapshot.tenantId(), snapshot.sourceWatermark(), Math::max);
        return new IndexedRuntime(runtimes.get(key), runtimeWatermarks.get(snapshot.tenantId()));
    }

    @Override
    public synchronized Optional<IndexedStatic> readStatic(String tenantId, String scenarioId,
                                                            long revision, String fingerprint) {
        if (!validScope(tenantId, scenarioId) || revision < 1 || !validFingerprint(fingerprint)) return Optional.empty();
        WorldStaticDependencySnapshot value = statics.get(new StaticKey(tenantId, scenarioId, revision));
        if (value == null || !value.fingerprint().equals(fingerprint)) return Optional.empty();
        return Optional.of(new IndexedStatic(value, staticWatermarks.getOrDefault(tenantId, value.sourceWatermark())));
    }

    @Override
    public synchronized Optional<IndexedRuntime> readRuntime(String tenantId, String runId, String fingerprint) {
        if (!validScope(tenantId, runId) || !validFingerprint(fingerprint)) return Optional.empty();
        WorldRuntimeConsumptionSnapshot value = runtimes.get(new RuntimeKey(tenantId, runId));
        if (value == null || !value.fingerprint().equals(fingerprint)) return Optional.empty();
        return Optional.of(new IndexedRuntime(value, runtimeWatermarks.getOrDefault(tenantId, value.sourceWatermark())));
    }

    @Override
    public synchronized List<IndexedStatic> staticSnapshots(String tenantId) {
        if (!validText(tenantId)) return List.of();
        long watermark = staticWatermarks.getOrDefault(tenantId, 1L);
        return statics.values().stream().filter(value -> value.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(WorldStaticDependencySnapshot::scenarioId)
                        .thenComparingLong(WorldStaticDependencySnapshot::scenarioRevision))
                .map(value -> new IndexedStatic(value, watermark)).toList();
    }

    @Override
    public synchronized List<IndexedRuntime> runtimeSnapshots(String tenantId) {
        if (!validText(tenantId)) return List.of();
        long watermark = runtimeWatermarks.getOrDefault(tenantId, 1L);
        return runtimes.values().stream().filter(value -> value.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(WorldRuntimeConsumptionSnapshot::runId))
                .map(value -> new IndexedRuntime(value, watermark)).toList();
    }

    @Override public synchronized long staticWatermark(String tenantId) {
        return staticWatermarks.getOrDefault(validText(tenantId) ? tenantId : "", 0L);
    }

    @Override public synchronized long runtimeWatermark(String tenantId) {
        return runtimeWatermarks.getOrDefault(validText(tenantId) ? tenantId : "", 0L);
    }

    private static void require(Object value) {
        if (!(value instanceof WorldStaticDependencySnapshot)
                && !(value instanceof WorldRuntimeConsumptionSnapshot)) throw WorldImpactSupport.fail(
                WorldImpactException.Code.INVALID_INPUT);
    }

    private static boolean validScope(String tenantId, String id) {
        return validText(tenantId) && validText(id);
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= WorldImpactSupport.MAX_TEXT
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validFingerprint(String value) {
        return value != null && WorldImpactSupport.FINGERPRINT.matcher(value).matches();
    }

    private static WorldImpactException conflict() {
        return WorldImpactSupport.fail(WorldImpactException.Code.INDEX_CONFLICT);
    }

    private record StaticKey(String tenantId, String scenarioId, long revision) { }
    private record RuntimeKey(String tenantId, String runId) { }
}
