package com.leanowtech.bloge.gateway.testing.world.impact;

import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractCompatibility;

import java.time.Instant;
import java.util.List;

/** Conservative static/runtime contract impact analyzer. */
public final class WorldContractImpactAnalyzer {
    /**
     * Derives index watermarks and availability from the authoritative repository.
     *
     * <p>An empty repository is intentionally not treated as an empty impact set. The resulting
     * report is explicitly gate-blocked until both index populations have a usable watermark.</p>
     */
    public WorldContractImpactReport analyze(WorldImpactSnapshotRepository repository,
                                              String tenantId, String contractId,
                                              LogicalResourceContract oldContract,
                                              LogicalResourceContract newContract,
                                              Instant evidenceWindowStart,
                                              Instant evidenceWindowEnd) {
        if (repository == null) throw invalid();
        String tenant = WorldImpactSupport.text(tenantId);
        try {
            List<WorldImpactSnapshotRepository.IndexedStatic> statics =
                    repository.staticSnapshots(tenant);
            List<WorldImpactSnapshotRepository.IndexedRuntime> runtimes =
                    repository.runtimeSnapshots(tenant);
            long staticWatermark = repository.staticWatermark(tenant);
            long runtimeWatermark = repository.runtimeWatermark(tenant);
            return analyze(tenant, contractId, oldContract, newContract, statics, runtimes,
                    staticWatermark, runtimeWatermark,
                    staticWatermark > 0 && !statics.isEmpty(),
                    runtimeWatermark > 0 && !runtimes.isEmpty(),
                    evidenceWindowStart, evidenceWindowEnd);
        } catch (WorldImpactException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * Compatibility entry point for callers that already own an index snapshot.
     *
     * <p>The supplied completeness flags are accepted only after the input is tenant-validated
     * and the requested contract has a non-empty static denominator. Missing or stale data creates
     * an explicit blocked report, never a safe empty result.</p>
     */
    public WorldContractImpactReport analyze(String tenantId, String contractId,
                                              LogicalResourceContract oldContract,
                                              LogicalResourceContract newContract,
                                              List<WorldImpactSnapshotRepository.IndexedStatic> statics,
                                              List<WorldImpactSnapshotRepository.IndexedRuntime> runtimes,
                                              long requiredStaticWatermark, long requiredRuntimeWatermark,
                                              boolean staticIndexComplete, boolean runtimeIndexComplete,
                                              Instant evidenceWindowStart, Instant evidenceWindowEnd) {
        if (statics == null || runtimes == null || requiredStaticWatermark < 0
                || requiredRuntimeWatermark < 0 || evidenceWindowStart == null
                || evidenceWindowEnd == null || oldContract == null || newContract == null
                || evidenceWindowEnd.isBefore(evidenceWindowStart)) throw invalid();
        String tenant = WorldImpactSupport.text(tenantId);
        String id = WorldImpactSupport.text(contractId);
        if (!id.equals(oldContract.contractId()) || !id.equals(newContract.contractId())) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
        }
        validateTenantScope(statics, runtimes, tenant);
        List<String> fullSet = staticScenarioIds(statics, tenant, id);
        boolean denominatorAvailable = staticIndexComplete && requiredStaticWatermark > 0
                && !statics.isEmpty() && !fullSet.isEmpty();
        boolean runtimeAvailable = runtimeIndexComplete && requiredRuntimeWatermark > 0
                && !runtimes.isEmpty();
        boolean stale = statics.stream().anyMatch(value -> value.stale()
                        || value.currentWatermark() < requiredStaticWatermark
                        || value.snapshot().sourceWatermark() > value.currentWatermark())
                || runtimes.stream().anyMatch(value -> value.stale()
                        || value.currentWatermark() < requiredRuntimeWatermark
                        || value.snapshot().sourceWatermark() > value.currentWatermark());
        WorldContractImpactReport.ScopeStatus scopeStatus;
        if (!denominatorAvailable || !runtimeAvailable) {
            scopeStatus = WorldContractImpactReport.ScopeStatus.DENOMINATOR_UNAVAILABLE;
        } else if (stale || !staticIndexComplete || !runtimeIndexComplete) {
            scopeStatus = WorldContractImpactReport.ScopeStatus.INDEX_STALE;
        } else {
            scopeStatus = WorldContractImpactReport.ScopeStatus.COMPLETE;
        }

        WorldContractImpactReport.Status status;
        List<String> affected;
        boolean conservative;
        if (scopeStatus != WorldContractImpactReport.ScopeStatus.COMPLETE) {
            status = WorldContractImpactReport.Status.UNKNOWN;
            affected = fullSet;
            conservative = true;
        } else {
            LogicalResourceContractCompatibility.Report compatibility;
            try {
                compatibility = LogicalResourceContractCompatibility.analyze(oldContract, newContract);
            } catch (RuntimeException failure) {
                throw invalid();
            }
            if (compatibility.status() == LogicalResourceContractCompatibility.Status.BREAKING) {
                status = WorldContractImpactReport.Status.BREAKING_CHANGE;
                affected = fullSet;
                conservative = false;
            } else if (compatibility.status() == LogicalResourceContractCompatibility.Status.COMPATIBLE) {
                status = WorldContractImpactReport.Status.COMPATIBLE_CHANGE;
                affected = runtimes.stream().flatMap(value -> value.snapshot().consumptions().stream()
                                .filter(consumption -> consumption.logicalContractId().equals(id))
                                .map(ignored -> value.snapshot().scenarioId()))
                        .distinct().sorted().toList();
                conservative = false;
            } else {
                status = WorldContractImpactReport.Status.UNKNOWN;
                affected = fullSet;
                conservative = true;
            }
        }
        long staticWatermark = maxWatermark(statics);
        long runtimeWatermark = maxWatermark(runtimes);
        return new WorldContractImpactReport(WorldImpactSupport.ALGORITHM, tenant, id,
                oldContract.contractFingerprint(), newContract.contractFingerprint(),
                staticWatermark, runtimeWatermark, evidenceWindowStart, evidenceWindowEnd,
                status, scopeStatus, affected, conservative, WorldImpactSupport.hash(
                WorldImpactSupport.material(
                        "algorithmVersion", WorldImpactSupport.ALGORITHM, "tenantId", tenant,
                        "contractId", id, "oldContractFingerprint", oldContract.contractFingerprint(),
                        "newContractFingerprint", newContract.contractFingerprint(),
                        "staticIndexWatermark", staticWatermark,
                        "runtimeIndexWatermark", runtimeWatermark,
                        "evidenceWindowStart", evidenceWindowStart,
                        "evidenceWindowEnd", evidenceWindowEnd, "status", status.name(),
                        "scopeStatus", scopeStatus.name(), "affectedScenarioIds", affected,
                        "conservativeFullSet", conservative)));
    }

    private static void validateTenantScope(
            List<WorldImpactSnapshotRepository.IndexedStatic> statics,
            List<WorldImpactSnapshotRepository.IndexedRuntime> runtimes,
            String tenant) {
        java.util.Set<String> staticKeys = new java.util.HashSet<>();
        java.util.Set<String> runtimeKeys = new java.util.HashSet<>();
        if (statics.stream().anyMatch(value -> value == null
                || !tenant.equals(value.snapshot().tenantId())
                || !staticKeys.add(value.snapshot().scenarioId() + "\u0000"
                + value.snapshot().scenarioRevision()))
                || runtimes.stream().anyMatch(value -> value == null
                || !tenant.equals(value.snapshot().tenantId())
                || !runtimeKeys.add(value.snapshot().runId()))) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.TENANT_SCOPE);
        }
    }

    private static List<String> staticScenarioIds(
            List<WorldImpactSnapshotRepository.IndexedStatic> values,
            String tenant, String contractId) {
        return values.stream()
                .filter(value -> tenant.equals(value.snapshot().tenantId()))
                .filter(value -> value.snapshot().dependencies().stream()
                        .anyMatch(dependency -> dependency.logicalContractId().equals(contractId)))
                .map(value -> value.snapshot().scenarioId()).distinct().sorted().toList();
    }

    private static long maxWatermark(List<? extends Object> values) {
        long max = 0;
        for (Object value : values) {
            if (value instanceof WorldImpactSnapshotRepository.IndexedStatic indexed) {
                max = Math.max(max, indexed.currentWatermark());
            }
            if (value instanceof WorldImpactSnapshotRepository.IndexedRuntime indexed) {
                max = Math.max(max, indexed.currentWatermark());
            }
        }
        return max;
    }

    private static WorldImpactException invalid() {
        return WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
    }
}
