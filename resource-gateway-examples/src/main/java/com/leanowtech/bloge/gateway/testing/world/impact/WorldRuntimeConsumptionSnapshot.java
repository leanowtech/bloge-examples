package com.leanowtech.bloge.gateway.testing.world.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable observed-consumption index derived only from verified terminal evidence. */
public record WorldRuntimeConsumptionSnapshot(
        String schemaVersion,
        String algorithmVersion,
        String tenantId,
        String scenarioId,
        long scenarioRevision,
        String scenarioFingerprint,
        String runId,
        String evidenceFingerprint,
        String targetGraphArtifactFingerprint,
        String compilationFingerprint,
        long sourceWatermark,
        Instant evidenceStartedAt,
        Instant evidenceCompletedAt,
        Instant generatedAt,
        List<Consumption> consumptions,
        String fingerprint) {

    public WorldRuntimeConsumptionSnapshot {
        if (!WorldImpactSupport.RUNTIME_SCHEMA.equals(schemaVersion)
                || !WorldImpactSupport.ALGORITHM.equals(algorithmVersion)) throw invalid();
        tenantId = WorldImpactSupport.text(tenantId);
        scenarioId = WorldImpactSupport.text(scenarioId);
        scenarioFingerprint = WorldImpactSupport.fingerprint(scenarioFingerprint);
        runId = WorldImpactSupport.text(runId);
        evidenceFingerprint = WorldImpactSupport.fingerprint(evidenceFingerprint);
        targetGraphArtifactFingerprint = WorldImpactSupport.fingerprint(targetGraphArtifactFingerprint);
        compilationFingerprint = WorldImpactSupport.fingerprint(compilationFingerprint);
        if (scenarioRevision < 1 || sourceWatermark < 1) throw invalid();
        evidenceStartedAt = WorldImpactSupport.instant(evidenceStartedAt);
        evidenceCompletedAt = WorldImpactSupport.instant(evidenceCompletedAt);
        generatedAt = WorldImpactSupport.instant(generatedAt);
        if (evidenceCompletedAt.isBefore(evidenceStartedAt)) throw invalid();
        consumptions = canonicalConsumptions(consumptions);
        fingerprint = WorldImpactSupport.fingerprint(fingerprint);
        if (!fingerprint.equals(computeFingerprint(schemaVersion, algorithmVersion, tenantId, scenarioId,
                scenarioRevision, scenarioFingerprint, runId, evidenceFingerprint, targetGraphArtifactFingerprint,
                compilationFingerprint, sourceWatermark, evidenceStartedAt, evidenceCompletedAt, consumptions))) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.FINGERPRINT_MISMATCH);
        }
    }

    public static WorldRuntimeConsumptionSnapshot create(String tenantId, String scenarioId,
                                                         long scenarioRevision, String scenarioFingerprint,
                                                         String runId, String evidenceFingerprint,
                                                         String targetGraphFingerprint, String compilationFingerprint,
                                                         long sourceWatermark, Instant evidenceStartedAt,
                                                         Instant evidenceCompletedAt, Instant generatedAt,
                                                         List<Consumption> consumptions) {
        String tenant = WorldImpactSupport.text(tenantId);
        String scenario = WorldImpactSupport.text(scenarioId);
        String run = WorldImpactSupport.text(runId);
        List<Consumption> normalized = canonicalConsumptions(consumptions);
        return new WorldRuntimeConsumptionSnapshot(WorldImpactSupport.RUNTIME_SCHEMA,
                WorldImpactSupport.ALGORITHM, tenant, scenario, scenarioRevision, scenarioFingerprint,
                run, evidenceFingerprint, targetGraphFingerprint, compilationFingerprint, sourceWatermark,
                evidenceStartedAt, evidenceCompletedAt, generatedAt, normalized,
                computeFingerprint(WorldImpactSupport.RUNTIME_SCHEMA, WorldImpactSupport.ALGORITHM, tenant,
                        scenario, scenarioRevision, scenarioFingerprint, run, evidenceFingerprint,
                        targetGraphFingerprint, compilationFingerprint, sourceWatermark, evidenceStartedAt,
                        evidenceCompletedAt, normalized));
    }

    private static List<Consumption> canonicalConsumptions(List<Consumption> values) {
        List<Consumption> copy = new ArrayList<>(WorldImpactSupport.list(values));
        copy.sort(Comparator.comparing(Consumption::logicalContractId).thenComparing(Consumption::worldRuleId));
        for (int index = 0; index < copy.size(); index++) {
            if (index > 0 && copy.get(index - 1).key().equals(copy.get(index).key())) throw invalid();
        }
        return List.copyOf(copy);
    }

    private static String computeFingerprint(String schemaVersion, String algorithmVersion,
                                             String tenantId, String scenarioId, long scenarioRevision,
                                             String scenarioFingerprint, String runId, String evidenceFingerprint,
                                             String targetGraphFingerprint, String compilationFingerprint,
                                             long sourceWatermark, Instant startedAt, Instant completedAt,
                                             List<Consumption> consumptions) {
        return WorldImpactSupport.hash(WorldImpactSupport.material(
                "schemaVersion", schemaVersion, "algorithmVersion", algorithmVersion,
                "tenantId", tenantId, "scenarioId", scenarioId, "scenarioRevision", scenarioRevision,
                "scenarioFingerprint", scenarioFingerprint, "runId", runId,
                "evidenceFingerprint", evidenceFingerprint,
                "targetGraphArtifactFingerprint", targetGraphFingerprint,
                "compilationFingerprint", compilationFingerprint, "sourceWatermark", sourceWatermark,
                "evidenceStartedAt", startedAt, "evidenceCompletedAt", completedAt,
                "consumptions", consumptions));
    }

    public record Consumption(String fixtureRuleId, String worldRuleId, String logicalContractId,
                              String logicalContractFingerprint, String worldSliceFingerprint,
                              String fragmentFingerprint, List<String> invocationSiteIds) {
        public Consumption {
            fixtureRuleId = WorldImpactSupport.text(fixtureRuleId);
            worldRuleId = WorldImpactSupport.text(worldRuleId);
            logicalContractId = WorldImpactSupport.text(logicalContractId);
            logicalContractFingerprint = WorldImpactSupport.fingerprint(logicalContractFingerprint);
            worldSliceFingerprint = WorldImpactSupport.fingerprint(worldSliceFingerprint);
            fragmentFingerprint = WorldImpactSupport.fingerprint(fragmentFingerprint);
            invocationSiteIds = uniqueSites(invocationSiteIds);
        }

        String key() {
            return logicalContractId + "\u0000" + worldRuleId;
        }
    }

    private static WorldImpactException invalid() {
        return WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
    }

    private static List<String> uniqueSites(List<String> values) {
        if (values == null || values.isEmpty()) throw invalid();
        List<String> result = values.stream().map(WorldImpactSupport::text).sorted().toList();
        for (int index = 1; index < result.size(); index++) {
            if (result.get(index - 1).equals(result.get(index))) throw invalid();
        }
        return List.copyOf(result);
    }
}
