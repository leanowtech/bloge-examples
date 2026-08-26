package com.leanowtech.bloge.gateway.testing.world.impact;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped immutable index port for static declarations and observed consumption. */
public interface WorldImpactSnapshotRepository {
    IndexedStatic upsertStatic(WorldStaticDependencySnapshot snapshot);

    IndexedRuntime upsertRuntime(WorldRuntimeConsumptionSnapshot snapshot);

    Optional<IndexedStatic> readStatic(String tenantId, String scenarioId, long revision,
                                      String fingerprint);

    Optional<IndexedRuntime> readRuntime(String tenantId, String runId, String fingerprint);

    List<IndexedStatic> staticSnapshots(String tenantId);

    List<IndexedRuntime> runtimeSnapshots(String tenantId);

    long staticWatermark(String tenantId);

    long runtimeWatermark(String tenantId);

    record IndexedStatic(WorldStaticDependencySnapshot snapshot, long currentWatermark) {
        public IndexedStatic {
            if (snapshot == null || currentWatermark < 1) throw WorldImpactSupport.fail(
                    WorldImpactException.Code.INVALID_INPUT);
        }

        public boolean stale() {
            return snapshot.sourceWatermark() < currentWatermark;
        }
    }

    record IndexedRuntime(WorldRuntimeConsumptionSnapshot snapshot, long currentWatermark) {
        public IndexedRuntime {
            if (snapshot == null || currentWatermark < 1) throw WorldImpactSupport.fail(
                    WorldImpactException.Code.INVALID_INPUT);
        }

        public boolean stale() {
            return snapshot.sourceWatermark() < currentWatermark;
        }
    }
}
