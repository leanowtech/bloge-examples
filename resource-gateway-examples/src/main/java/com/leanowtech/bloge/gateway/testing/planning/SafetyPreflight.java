package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates v1 protocol activation boundaries before selectors are resolved or code is executed.
 *
 * <p>Schema fields for time, retry occurrence, stream, and replay are intentionally present for
 * wire stability. Accepting them before deterministic engine support exists would be worse than a
 * version error, so this preflight rejects every reserved field explicitly.</p>
 */
public class SafetyPreflight {

    /**
     * Validates the bundle and throws one bounded aggregate rejection when it is not executable.
     *
     * @param bundle frozen fixture bundle
     * @param authorizedPurpose server-minted execution purpose
     * @param targetFingerprint actual frozen target fingerprint
     */
    public void validate(FixtureBundle bundle, String authorizedPurpose, String targetFingerprint) {
        List<String> diagnostics = new ArrayList<>();
        if (bundle == null) {
            reject("Fixture bundle is required.");
        }
        if (!FixtureBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            diagnostics.add("Unsupported fixture bundle schemaVersion: " + bundle.schemaVersion());
        }
        if (bundle.fixtureBundleId().isBlank()) {
            diagnostics.add("fixtureBundleId is required.");
        }
        if (bundle.revision() <= 0) {
            diagnostics.add("fixture bundle revision must be positive.");
        }
        if (targetFingerprint == null || targetFingerprint.isBlank()) {
            diagnostics.add("A frozen target fingerprint is required.");
        }
        if (!bundle.targetFingerprint().isBlank()
                && !bundle.targetFingerprint().equals(targetFingerprint)) {
            diagnostics.add("Fixture target fingerprint does not match the selected artifact.");
        }
        if (authorizedPurpose == null || authorizedPurpose.isBlank()) {
            diagnostics.add("A server-authorized execution purpose is required.");
        } else if (authorizedPurpose.toUpperCase(java.util.Locale.ROOT).contains("PRODUCTION")) {
            diagnostics.add("Production execution purpose cannot carry a test control plan.");
        }
        if (bundle.logicalClock() != null || bundle.randomSeed() != null) {
            diagnostics.add("logicalClock and randomSeed are reserved until deterministic engine services exist.");
        }

        Set<String> ruleIds = new HashSet<>();
        for (int i = 0; i < bundle.rules().size(); i++) {
            validateRule(bundle.rules().get(i), i, ruleIds, diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            throw new ControlPlanRejectedException("CONTROL_PLAN_REJECTED", bounded(diagnostics));
        }
    }

    private static void validateRule(FixtureRule rule, int index, Set<String> ruleIds,
                                     List<String> diagnostics) {
        String prefix = "rules[" + index + "]";
        if (rule == null) {
            diagnostics.add(prefix + " must not be null.");
            return;
        }
        if (!FixtureRule.SCHEMA_VERSION.equals(rule.schemaVersion())) {
            diagnostics.add(prefix + " has an unsupported schemaVersion.");
        }
        if (rule.ruleId().isBlank()) {
            diagnostics.add(prefix + ".ruleId is required.");
        } else if (!ruleIds.add(rule.ruleId())) {
            diagnostics.add(prefix + ".ruleId duplicates '" + rule.ruleId() + "'.");
        }
        if (!rule.selector().attempts().isEmpty() || !rule.selector().occurrences().isEmpty()) {
            diagnostics.add(prefix + " uses reserved attempt/occurrence selectors.");
        }
        if (!rule.selector().functionRef().isBlank()) {
            diagnostics.add(prefix + " uses functionRef, which requires engine FunctionCallSite support.");
        }
        FixtureRule.Behavior behavior = rule.behavior();
        if (Set.of(FixtureRule.BehaviorKind.DELAY, FixtureRule.BehaviorKind.TIMEOUT,
                FixtureRule.BehaviorKind.STREAM, FixtureRule.BehaviorKind.REPLAY).contains(behavior.kind())) {
            diagnostics.add(prefix + " uses reserved behavior " + behavior.kind() + ".");
        }
        if (behavior.after() != null || !behavior.sequence().isEmpty() || !behavior.replayRef().isBlank()) {
            diagnostics.add(prefix + " populates reserved time, sequence, or replay fields.");
        }
        if (behavior.boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                && !"httpResource".equals(rule.selector().operatorRef())
                && rule.selector().resourceRef().isBlank()) {
            diagnostics.add(prefix + " requests TRANSPORT without a resource selector.");
        }
        if (!behavior.rawBody().isBlank() && behavior.statusCode() == null) {
            diagnostics.add(prefix + " rawBody requires statusCode.");
        }
        if (rule.schemaCheck().mode() == FixtureRule.SchemaCheckMode.WAIVED
                && rule.schemaCheck().waiverReason().isBlank()) {
            diagnostics.add(prefix + " schema waiver requires waiverReason.");
        }
        if (rule.consumption().maxUses() > 0
                && rule.consumption().minUses() > rule.consumption().maxUses()) {
            diagnostics.add(prefix + " minUses cannot exceed maxUses.");
        }
        rule.selector().match().boundedRegex().forEach((path, expression) -> {
            String reason = BoundedRegexPolicy.rejectionReason(expression);
            if (!reason.isEmpty()) {
                diagnostics.add(prefix + ".selector.match.boundedRegex['" + path + "'] " + reason + ".");
            }
        });
    }

    private static List<String> bounded(List<String> diagnostics) {
        return diagnostics.stream().limit(20).map(value -> value.length() <= 300
                ? value : value.substring(0, 300)).toList();
    }

    private static void reject(String diagnostic) {
        throw new ControlPlanRejectedException("CONTROL_PLAN_REJECTED", List.of(diagnostic));
    }
}
