package com.leanowtech.bloge.gateway.testing.world.impact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares declared static dependencies with independently observed runtime consumption. */
public record WorldImpactReconciliation(
        String tenantId,
        String scenarioId,
        List<Entry> entries,
        boolean publicationBlocked,
        String fingerprint) {
    public enum Classification {
        DECLARED_AND_OBSERVED,
        DECLARED_ONLY,
        OBSERVED_ONLY
    }

    public WorldImpactReconciliation {
        tenantId = WorldImpactSupport.text(tenantId);
        scenarioId = WorldImpactSupport.text(scenarioId);
        entries = canonical(entries);
        fingerprint = WorldImpactSupport.fingerprint(fingerprint);
        String expected = WorldImpactSupport.hash(java.util.Map.of(
                "tenantId", tenantId, "scenarioId", scenarioId, "entries", entries,
                "publicationBlocked", publicationBlocked));
        if (!fingerprint.equals(expected)) throw WorldImpactSupport.fail(WorldImpactException.Code.FINGERPRINT_MISMATCH);
        if (publicationBlocked != entries.stream().anyMatch(entry -> entry.classification() == Classification.OBSERVED_ONLY)) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
        }
    }

    public static WorldImpactReconciliation reconcile(WorldStaticDependencySnapshot declared,
                                                      WorldRuntimeConsumptionSnapshot observed) {
        if (declared == null || observed == null || !declared.tenantId().equals(observed.tenantId())
                || !declared.scenarioId().equals(observed.scenarioId())
                || !declared.scenarioFingerprint().equals(observed.scenarioFingerprint())
                || !declared.targetGraphArtifactFingerprint().equals(observed.targetGraphArtifactFingerprint())) {
            throw WorldImpactSupport.fail(WorldImpactException.Code.TENANT_SCOPE);
        }
        Set<Key> declaredKeys = new LinkedHashSet<>();
        Map<Key, WorldStaticDependencySnapshot.Dependency> declaredFacts = new HashMap<>();
        declared.dependencies().forEach(dependency -> dependency.invocationSiteIds()
                .forEach(site -> {
                    Key key = new Key(dependency.logicalContractId(), site);
                    if (!declaredKeys.add(key) || declaredFacts.put(key, dependency) != null) {
                        throw WorldImpactSupport.fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                    }
                }));
        Set<Key> observedKeys = new LinkedHashSet<>();
        Map<Key, WorldRuntimeConsumptionSnapshot.Consumption> observedFacts = new HashMap<>();
        observed.consumptions().forEach(consumption -> consumption.invocationSiteIds()
                .forEach(site -> {
                    Key key = new Key(consumption.logicalContractId(), site);
                    if (!observedKeys.add(key) || observedFacts.put(key, consumption) != null) {
                        throw WorldImpactSupport.fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                    }
                }));
        for (Key key : declaredKeys) {
            if (observedKeys.contains(key)
                    && !sameChain(declaredFacts.get(key), observedFacts.get(key))) {
                throw WorldImpactSupport.fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
        }
        Set<Key> all = new LinkedHashSet<>(declaredKeys);
        all.addAll(observedKeys);
        List<Entry> entries = all.stream().map(key -> new Entry(key.logicalContractId(), key.invocationSiteId(),
                declaredKeys.contains(key) && observedKeys.contains(key) ? Classification.DECLARED_AND_OBSERVED
                        : declaredKeys.contains(key) ? Classification.DECLARED_ONLY : Classification.OBSERVED_ONLY)).toList();
        boolean blocked = entries.stream().anyMatch(entry -> entry.classification() == Classification.OBSERVED_ONLY);
        return new WorldImpactReconciliation(declared.tenantId(), declared.scenarioId(), entries, blocked,
                WorldImpactSupport.hash(java.util.Map.of("tenantId", declared.tenantId(),
                        "scenarioId", declared.scenarioId(), "entries", canonical(entries),
                        "publicationBlocked", blocked)));
    }

    private static boolean sameChain(WorldStaticDependencySnapshot.Dependency declared,
                                     WorldRuntimeConsumptionSnapshot.Consumption observed) {
        return declared.ruleId().equals(observed.fixtureRuleId())
                && declared.ruleId().equals(observed.worldRuleId())
                && declared.logicalContractId().equals(observed.logicalContractId())
                && declared.logicalContractFingerprint().equals(observed.logicalContractFingerprint())
                && declared.worldSliceFingerprint().equals(observed.worldSliceFingerprint())
                && declared.fragmentFingerprint().equals(observed.fragmentFingerprint());
    }

    private static List<Entry> canonical(List<Entry> values) {
        if (values == null || values.size() > WorldImpactSupport.MAX_ENTRIES
                || values.stream().anyMatch(value -> value == null)) throw WorldImpactSupport.fail(
                WorldImpactException.Code.LIMIT_EXCEEDED);
        List<Entry> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(Entry::logicalContractId).thenComparing(Entry::invocationSiteId));
        for (int index = 0; index < copy.size(); index++) {
            if (index > 0 && copy.get(index - 1).key().equals(copy.get(index).key())) throw WorldImpactSupport.fail(
                    WorldImpactException.Code.INVALID_INPUT);
        }
        return List.copyOf(copy);
    }

    public record Entry(String logicalContractId, String invocationSiteId, Classification classification) {
        public Entry {
            logicalContractId = WorldImpactSupport.text(logicalContractId);
            invocationSiteId = WorldImpactSupport.text(invocationSiteId);
            if (classification == null) throw WorldImpactSupport.fail(WorldImpactException.Code.INVALID_INPUT);
        }

        private Key key() {
            return new Key(logicalContractId, invocationSiteId);
        }
    }

    private record Key(String logicalContractId, String invocationSiteId) { }
}
