package com.leanowtech.bloge.gateway.testing.world.migration;

import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.world.draft.WorldDraftRule;

/**
 * Server-owned port for completing one World rule after migration prerequisites are supplied.
 *
 * <p>The migration package only resolves an exact, read-only legacy rule. A concrete adapter may
 * then compile its governed fragment and return the existing {@link WorldDraftRule}; publication
 * is deliberately outside this port.</p>
 */
@FunctionalInterface
public interface WorldMigrationDraftMaterializer {
    Result materialize(Request request);

    /** Payload-bearing source data is only available to a server-owned implementation. */
    final class Request {
        private final WorldMigrationSource.Access access;
        private final WorldDraftMaterializationPlan.RulePlan plan;
        private final FixtureRule sourceRule;

        Request(WorldMigrationSource.Access access,
                WorldDraftMaterializationPlan.RulePlan plan,
                FixtureRule sourceRule) {
            if (access == null || plan == null || sourceRule == null
                    || !plan.sourceRuleId().equals(sourceRule.ruleId())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_PREREQUISITE_MISSING);
            }
            this.access = access;
            this.plan = plan;
            this.sourceRule = sourceRule;
        }

        WorldMigrationSource.Access access() { return access; }
        WorldDraftMaterializationPlan.RulePlan plan() { return plan; }
        FixtureRule sourceRule() { return sourceRule; }

        @Override
        public String toString() {
            return "WorldMigrationMaterializationRequest[ruleId=" + plan.sourceRuleId()
                    + ",targetFingerprint=" + plan.legacyRef().fixtureBundleFingerprint() + "]";
        }
    }

    /** Payload-free result describing an unpublished existing World draft rule. */
    record Result(WorldDraftRule rule, String fingerprint) {
        public Result {
            if (rule == null || !MigrationSupport.FINGERPRINT.matcher(fingerprint == null ? "" : fingerprint).matches()) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_INVALID);
            }
            if (!fingerprint.equals(rule.fingerprint())) {
                throw MigrationSupport.fail(WorldMigrationException.Code.MATERIALIZATION_INVALID);
            }
        }
    }
}
