package com.leanowtech.bloge.gateway.testing.world.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Payload-free contract impact result consumed by release gates. */
public record WorldContractImpactReport(
        String algorithmVersion,
        String tenantId,
        String contractId,
        String oldContractFingerprint,
        String newContractFingerprint,
        long staticIndexWatermark,
        long runtimeIndexWatermark,
        Instant evidenceWindowStart,
        Instant evidenceWindowEnd,
        Status status,
        ScopeStatus scopeStatus,
        List<String> affectedScenarioIds,
        boolean conservativeFullSet,
        String fingerprint) {
    public enum Status {
        COMPATIBLE_CHANGE,
        BREAKING_CHANGE,
        UNKNOWN
    }

    /** Explicit denominator/index state; non-complete scope always blocks release. */
    public enum ScopeStatus {
        COMPLETE,
        DENOMINATOR_UNAVAILABLE,
        INDEX_STALE,
        TENANT_CONTAMINATION
    }

    public WorldContractImpactReport {
        if (!WorldImpactSupport.ALGORITHM.equals(algorithmVersion)) throw invalid();
        tenantId = WorldImpactSupport.text(tenantId);
        contractId = WorldImpactSupport.text(contractId);
        oldContractFingerprint = WorldImpactSupport.fingerprint(oldContractFingerprint);
        newContractFingerprint = WorldImpactSupport.fingerprint(newContractFingerprint);
        if (staticIndexWatermark < 0 || runtimeIndexWatermark < 0 || status == null
                || scopeStatus == null
                || evidenceWindowStart == null || evidenceWindowEnd == null
                || evidenceWindowEnd.isBefore(evidenceWindowStart)) throw invalid();
        if (affectedScenarioIds == null || affectedScenarioIds.size() > WorldImpactSupport.MAX_ENTRIES) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.LIMIT_EXCEEDED);
        }
        List<String> normalizedAffected = new ArrayList<>(affectedScenarioIds.stream()
                .map(WorldImpactSupport::text).toList());
        if (normalizedAffected.size() != new HashSet<>(normalizedAffected).size()) {
            throw invalid();
        }
        normalizedAffected.sort(String::compareTo);
        affectedScenarioIds = List.copyOf(normalizedAffected);
        fingerprint = WorldImpactSupport.fingerprint(fingerprint);
        String expected = WorldImpactSupport.hash(WorldImpactSupport.material("algorithmVersion", algorithmVersion,
                "tenantId", tenantId, "contractId", contractId,
                "oldContractFingerprint", oldContractFingerprint, "newContractFingerprint", newContractFingerprint,
                "staticIndexWatermark", staticIndexWatermark, "runtimeIndexWatermark", runtimeIndexWatermark,
                "evidenceWindowStart", evidenceWindowStart, "evidenceWindowEnd", evidenceWindowEnd,
                "status", status.name(), "scopeStatus", scopeStatus.name(),
                "affectedScenarioIds", affectedScenarioIds,
                "conservativeFullSet", conservativeFullSet));
        if (!fingerprint.equals(expected)) throw WorldImpactSupport.fail(WorldImpactException.Code.FINGERPRINT_MISMATCH);
        if (status == Status.UNKNOWN && !conservativeFullSet) throw invalid();
        if (status != Status.UNKNOWN && scopeStatus != ScopeStatus.COMPLETE) throw invalid();
    }

    /** Whether consumers must block publication regardless of the affected-id set. */
    public boolean gateBlocked() {
        return scopeStatus != ScopeStatus.COMPLETE || status == Status.UNKNOWN || conservativeFullSet;
    }

    private static WorldImpactException invalid() {
        return WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
    }
}
