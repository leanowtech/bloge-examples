package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.List;

/**
 * Payload-free projection of the exact execution control that would govern one graph run.
 *
 * <p>The response is produced by the same target, fixture, replay, secret, and planner boundary
 * used by {@link TestExecutionApiService#execute}. It supports a safety review without executing
 * an operator or exposing fixture material.</p>
 */
public record TestExecutionPreflightResponse(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        TestExecutionApiResponse.ResolvedFixtureBundleRef fixtureBundleRef,
        EffectiveExecutionPlan effectivePlan,
        List<InvocationSiteDescriptor> invocationSites,
        List<RulePolicyDescriptor> rulePolicies
) {
    public static final String SCHEMA_VERSION = "bloge.testExecutionPreflightResponse.v1";

    public TestExecutionPreflightResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported test execution preflight schemaVersion");
        }
        if (target == null || fixtureBundleRef == null || effectivePlan == null) {
            throw new IllegalArgumentException(
                    "Resolved target, fixture, and effective plan are required");
        }
        invocationSites = invocationSites == null ? List.of() : List.copyOf(invocationSites);
        rulePolicies = rulePolicies == null ? List.of() : List.copyOf(rulePolicies);
    }

    /** Payload-free runtime binding facts for one structural invocation site. */
    public record InvocationSiteDescriptor(InvocationSite site, String sideEffectType) {
        public InvocationSiteDescriptor {
            if (site == null) {
                throw new IllegalArgumentException("Invocation site is required");
            }
            sideEffectType = sideEffectType == null ? "" : sideEffectType.trim();
            if (sideEffectType.isEmpty()) {
                throw new IllegalArgumentException("sideEffectType is required");
            }
        }
    }

    /** Consumption and fallback policy stripped of behavior payload material. */
    public record RulePolicyDescriptor(
            String ruleId,
            FixtureRule.BehaviorKind behavior,
            FixtureRule.DoubleBoundary boundary,
            boolean required,
            int minUses,
            int maxUses,
            FixtureRule.UnmatchedAction onUnmatched,
            FixtureRule.ExhaustedAction onExhausted,
            FixtureRule.SchemaCheckMode schemaCheckMode
    ) {
        public RulePolicyDescriptor {
            ruleId = ruleId == null ? "" : ruleId.trim();
            if (ruleId.isEmpty() || behavior == null || boundary == null
                    || onUnmatched == null || onExhausted == null || schemaCheckMode == null
                    || minUses < 0 || maxUses < 0) {
                throw new IllegalArgumentException("Complete bounded rule policy is required");
            }
        }
    }
}
