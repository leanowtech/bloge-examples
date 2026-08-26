package com.leanowtech.bloge.gateway.testing.world.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable, tenant-scoped declaration index for one exact Scenario revision. */
public record WorldStaticDependencySnapshot(
        String schemaVersion,
        String algorithmVersion,
        String tenantId,
        String scenarioId,
        long scenarioRevision,
        String scenarioFingerprint,
        String worldModelId,
        long worldRevision,
        String worldFingerprint,
        String targetGraphArtifactFingerprint,
        long sourceWatermark,
        Instant generatedAt,
        List<Dependency> dependencies,
        String fingerprint) {

    public WorldStaticDependencySnapshot {
        if (!WorldImpactSupport.STATIC_SCHEMA.equals(schemaVersion)
                || !WorldImpactSupport.ALGORITHM.equals(algorithmVersion)) throw invalid();
        tenantId = WorldImpactSupport.text(tenantId);
        scenarioId = WorldImpactSupport.text(scenarioId);
        scenarioFingerprint = WorldImpactSupport.fingerprint(scenarioFingerprint);
        worldModelId = WorldImpactSupport.text(worldModelId);
        worldFingerprint = WorldImpactSupport.fingerprint(worldFingerprint);
        targetGraphArtifactFingerprint = WorldImpactSupport.fingerprint(targetGraphArtifactFingerprint);
        if (scenarioRevision < 1 || worldRevision < 1 || sourceWatermark < 1) throw invalid();
        generatedAt = WorldImpactSupport.instant(generatedAt);
        dependencies = canonicalDependencies(dependencies);
        fingerprint = WorldImpactSupport.fingerprint(fingerprint);
        if (!fingerprint.equals(computeFingerprint(schemaVersion, algorithmVersion, tenantId, scenarioId,
                scenarioRevision, scenarioFingerprint, worldModelId, worldRevision, worldFingerprint,
                targetGraphArtifactFingerprint, sourceWatermark, dependencies))) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.FINGERPRINT_MISMATCH);
        }
    }

    public static WorldStaticDependencySnapshot create(String tenantId, String scenarioId,
                                                        long scenarioRevision, String scenarioFingerprint,
                                                        String worldModelId, long worldRevision,
                                                        String worldFingerprint, String targetGraphFingerprint,
                                                        long sourceWatermark, Instant generatedAt,
                                                        List<Dependency> dependencies) {
        String tenant = WorldImpactSupport.text(tenantId);
        String scenario = WorldImpactSupport.text(scenarioId);
        List<Dependency> normalized = canonicalDependencies(dependencies);
        return new WorldStaticDependencySnapshot(WorldImpactSupport.STATIC_SCHEMA,
                WorldImpactSupport.ALGORITHM, tenant, scenario, scenarioRevision,
                scenarioFingerprint, worldModelId, worldRevision, worldFingerprint,
                targetGraphFingerprint, sourceWatermark, generatedAt, normalized,
                computeFingerprint(WorldImpactSupport.STATIC_SCHEMA, WorldImpactSupport.ALGORITHM,
                        tenant, scenario, scenarioRevision, scenarioFingerprint, worldModelId,
                        worldRevision, worldFingerprint, targetGraphFingerprint, sourceWatermark, normalized));
    }

    private static List<Dependency> canonicalDependencies(List<Dependency> values) {
        List<Dependency> copy = new ArrayList<>(WorldImpactSupport.list(values));
        copy.sort(Comparator.comparing(Dependency::logicalContractId).thenComparing(Dependency::ruleId));
        for (int index = 0; index < copy.size(); index++) {
            if (index > 0 && copy.get(index - 1).key().equals(copy.get(index).key())) throw invalid();
        }
        return List.copyOf(copy);
    }

    private static String computeFingerprint(String schemaVersion, String algorithmVersion,
                                             String tenantId, String scenarioId, long scenarioRevision,
                                             String scenarioFingerprint, String worldModelId, long worldRevision,
                                             String worldFingerprint, String targetGraphFingerprint,
                                             long sourceWatermark, List<Dependency> dependencies) {
        return WorldImpactSupport.hash(WorldImpactSupport.material(
                "schemaVersion", schemaVersion, "algorithmVersion", algorithmVersion,
                "tenantId", tenantId, "scenarioId", scenarioId, "scenarioRevision", scenarioRevision,
                "scenarioFingerprint", scenarioFingerprint, "worldModelId", worldModelId,
                "worldRevision", worldRevision, "worldFingerprint", worldFingerprint,
                "targetGraphArtifactFingerprint", targetGraphFingerprint,
                "sourceWatermark", sourceWatermark, "dependencies", dependencies));
    }

    public record Dependency(String ruleId, String logicalContractId, String logicalContractFingerprint,
                             String worldSliceFingerprint, String fragmentFingerprint,
                             String targetGraphArtifactFingerprint, List<String> invocationSiteIds) {
        public Dependency {
            ruleId = WorldImpactSupport.text(ruleId);
            logicalContractId = WorldImpactSupport.text(logicalContractId);
            logicalContractFingerprint = WorldImpactSupport.fingerprint(logicalContractFingerprint);
            worldSliceFingerprint = WorldImpactSupport.fingerprint(worldSliceFingerprint);
            fragmentFingerprint = WorldImpactSupport.fingerprint(fragmentFingerprint);
            targetGraphArtifactFingerprint = WorldImpactSupport.fingerprint(targetGraphArtifactFingerprint);
            invocationSiteIds = uniqueSites(invocationSiteIds);
            if (invocationSiteIds.isEmpty() || invocationSiteIds.size() > WorldImpactSupport.MAX_ENTRIES) throw invalid();
            if (invocationSiteIds.stream().anyMatch(value -> value.isBlank())) throw invalid();
        }

        String key() {
            return logicalContractId + "\u0000" + ruleId;
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
