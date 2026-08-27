package com.leanowtech.bloge.gateway.testing.world.migration;

import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * An unpublished, server-resolvable World draft plan.
 *
 * <p>This is intentionally a plan rather than a second World model. It points at the governed
 * legacy rule and carries only the information needed by a server-owned materializer. It never
 * claims that a World asset has already been materialized.</p>
 */
public record WorldDraftMaterializationPlan(
        String draftId,
        String tenantId,
        String targetGraphArtifactFingerprint,
        Readiness readiness,
        List<RulePlan> rules,
        List<String> prerequisites,
        String fingerprint
) {
    public WorldDraftMaterializationPlan {
        draftId = MigrationSupport.text(draftId);
        tenantId = MigrationSupport.text(tenantId);
        targetGraphArtifactFingerprint = MigrationSupport.fingerprint(targetGraphArtifactFingerprint);
        if (readiness == null) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        rules = sortedRules(MigrationSupport.list(rules));
        prerequisites = MigrationSupport.sorted(MigrationSupport.list(prerequisites), Comparator.naturalOrder());
        if (rules.isEmpty()) {
            throw MigrationSupport.fail(WorldMigrationException.Code.MAPPING_MISSING);
        }
        if (rules.stream().map(RulePlan::sourceRuleId).distinct().count() != rules.size()) {
            throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        boolean ready = prerequisites.isEmpty() && rules.stream().allMatch(RulePlan::exactInputAvailable);
        if (readiness == Readiness.READY_TO_MATERIALIZE != ready) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
        fingerprint = MigrationSupport.fingerprint(fingerprint);
        if (!fingerprint.equals(computeFingerprint(draftId, tenantId, targetGraphArtifactFingerprint,
                readiness, rules, prerequisites))) {
            throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_TAMPERED);
        }
    }

    public static WorldDraftMaterializationPlan create(String draftId, String tenantId,
                                                        String targetFingerprint,
                                                        List<RulePlan> rules,
                                                        List<String> prerequisites) {
        String id = MigrationSupport.text(draftId);
        String tenant = MigrationSupport.text(tenantId);
        String target = MigrationSupport.fingerprint(targetFingerprint);
        List<RulePlan> orderedRules = sortedRules(MigrationSupport.list(rules));
        List<String> orderedPrerequisites = MigrationSupport.sorted(MigrationSupport.list(prerequisites),
                Comparator.naturalOrder());
        Readiness readiness = orderedPrerequisites.isEmpty()
                && orderedRules.stream().allMatch(RulePlan::exactInputAvailable)
                ? Readiness.READY_TO_MATERIALIZE : Readiness.NEEDS_PREREQUISITES;
        return new WorldDraftMaterializationPlan(id, tenant, target, readiness,
                orderedRules, orderedPrerequisites,
                computeFingerprint(id, tenant, target, readiness,
                        orderedRules, orderedPrerequisites));
    }

    public boolean readyToMaterialize() {
        return readiness == Readiness.READY_TO_MATERIALIZE;
    }

    private static String computeFingerprint(String draftId, String tenantId, String targetFingerprint,
                                             Readiness readiness, List<RulePlan> rules,
                                             List<String> prerequisites) {
        return MigrationSupport.hash(MigrationSupport.material(
                "draftId", draftId,
                "tenantId", tenantId,
                "targetGraphArtifactFingerprint", targetFingerprint,
                "readiness", readiness.name(),
                "rules", rules,
                "prerequisites", prerequisites));
    }

    private static List<RulePlan> sortedRules(List<RulePlan> values) {
        List<RulePlan> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(RulePlan::sourceRuleId));
        return List.copyOf(copy);
    }

    /** Exact address of the legacy fixture rule; no request/response payload is embedded. */
    public record LegacyFixtureRuleRef(String fixtureBundleId, long fixtureRevision,
                                       String fixtureBundleFingerprint, String ruleId) {
        public LegacyFixtureRuleRef {
            fixtureBundleId = MigrationSupport.text(fixtureBundleId);
            fixtureBundleFingerprint = MigrationSupport.fingerprint(fixtureBundleFingerprint);
            ruleId = MigrationSupport.text(ruleId);
            if (fixtureRevision < 1) {
                throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            }
        }

    }

    /** Payload-free executable intent for one legacy rule. */
    public record RulePlan(
            String sourceRuleId,
            LegacyFixtureRuleRef legacyRef,
            MaterializationKind kind,
            FixtureRule.BehaviorKind behavior,
            String logicalContractId,
            String logicalContractFingerprint,
            List<String> invocationSiteIds,
            String resultFingerprint,
            String replayRef,
            String errorCode,
            boolean explorationOnly,
            boolean exactInputAvailable
    ) {
        public RulePlan {
            sourceRuleId = MigrationSupport.text(sourceRuleId);
            if (legacyRef == null || kind == null || behavior == null) {
                throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            }
            if (!sourceRuleId.equals(legacyRef.ruleId())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
            }
            logicalContractId = logicalContractId == null ? "" : logicalContractId.trim();
            logicalContractFingerprint = logicalContractFingerprint == null
                    ? "" : logicalContractFingerprint.trim();
            if (!logicalContractId.isBlank()) MigrationSupport.text(logicalContractId);
            if (!logicalContractFingerprint.isBlank()) MigrationSupport.fingerprint(logicalContractFingerprint);
            invocationSiteIds = MigrationSupport.sorted(MigrationSupport.list(invocationSiteIds),
                    Comparator.naturalOrder());
            if (invocationSiteIds.isEmpty()
                    || invocationSiteIds.size() != new HashSet<>(invocationSiteIds).size()) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MAPPING_MISSING);
            }
            resultFingerprint = optionalFingerprint(resultFingerprint);
            replayRef = optionalText(replayRef);
            errorCode = optionalText(errorCode);
            if (!explorationOnly && resultFingerprint.isBlank() && replayRef.isBlank() && errorCode.isBlank()) {
                throw MigrationSupport.fail(WorldMigrationException.Code.INVALID_INPUT);
            }
        }

        private static String optionalFingerprint(String value) {
            String normalized = value == null ? "" : value.trim();
            return normalized.isBlank() ? "" : MigrationSupport.fingerprint(normalized);
        }

        private static String optionalText(String value) {
            if (value == null || value.isBlank()) return "";
            return MigrationSupport.text(value);
        }

    }

    public enum MaterializationKind {
        RETURN,
        REPLAY,
        FAILURE,
        SCHEMA_STANDIN
    }

    /** Readiness of a plan, deliberately distinct from the lifecycle of a World draft asset. */
    public enum Readiness {
        NEEDS_PREREQUISITES,
        READY_TO_MATERIALIZE
    }
}
