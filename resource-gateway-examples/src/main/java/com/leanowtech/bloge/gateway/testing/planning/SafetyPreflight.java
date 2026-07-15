package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates v1 protocol activation boundaries before selectors are resolved or code is executed.
 *
 * <p>Logical DELAY/TIMEOUT controls are accepted only with an explicit run clock. REPLAY is
 * accepted only when every exact reference was governed and frozen before compilation. Dynamic
 * attempt/occurrence selectors are bounded and canonical; stream, sequence, and deterministic
 * random fields remain fail-closed reservations.</p>
 */
public class SafetyPreflight {

    private static final Duration MAX_LOGICAL_ADVANCE = Duration.ofDays(365);
    private static final int MAX_SELECTOR_COORDINATES = 100;
    private static final int MAX_SELECTOR_COORDINATE = 100_000;

    /**
     * Validates the bundle and throws one bounded aggregate rejection when it is not executable.
     *
     * @param bundle frozen fixture bundle
     * @param authorizedPurpose server-minted execution purpose
     * @param targetFingerprint actual frozen target fingerprint
     */
    public void validate(FixtureBundle bundle, String authorizedPurpose, String targetFingerprint) {
        validate(bundle, authorizedPurpose, targetFingerprint, ResolvedReplayPayloads.empty());
    }

    /**
     * Validates a bundle and its exact pre-resolved replay dependency closure.
     *
     * @param bundle frozen fixture bundle
     * @param authorizedPurpose server-minted execution purpose
     * @param targetFingerprint actual frozen target fingerprint
     * @param replayPayloads governed payloads resolved before planner entry
     */
    public void validate(FixtureBundle bundle, String authorizedPurpose, String targetFingerprint,
                         ResolvedReplayPayloads replayPayloads) {
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
        if (bundle.randomSeed() != null) {
            diagnostics.add("randomSeed is reserved until a deterministic random service exists.");
        }

        Set<String> ruleIds = new HashSet<>();
        Set<String> replayRefs = new HashSet<>();
        for (int i = 0; i < bundle.rules().size(); i++) {
            validateRule(bundle.rules().get(i), i, ruleIds, diagnostics,
                    bundle.logicalClock() != null, replayRefs);
        }
        ResolvedReplayPayloads resolved = replayPayloads == null
                ? ResolvedReplayPayloads.empty() : replayPayloads;
        replayRefs.stream().filter(ref -> !resolved.references().contains(ref)).sorted()
                .forEach(ref -> diagnostics.add("Replay dependency was not resolved: " + ref));
        resolved.references().stream().filter(ref -> !replayRefs.contains(ref)).sorted()
                .forEach(ref -> diagnostics.add("Resolved replay dependency is not referenced: " + ref));
        if (replayRefs.size() != resolved.references().size()) {
            diagnostics.add("Replay dependency closure must exactly match fixture REPLAY rules.");
        }
        if (!diagnostics.isEmpty()) {
            throw new ControlPlanRejectedException("CONTROL_PLAN_REJECTED", bounded(diagnostics));
        }
    }

    private static void validateRule(FixtureRule rule, int index, Set<String> ruleIds,
                                     List<String> diagnostics, boolean logicalClockConfigured,
                                     Set<String> replayRefs) {
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
        validateCoordinates(prefix + ".selector.attempts", rule.selector().attempts(), diagnostics);
        validateCoordinates(prefix + ".selector.occurrences", rule.selector().occurrences(), diagnostics);
        if (!rule.selector().functionRef().isBlank()) {
            diagnostics.add(prefix + " uses functionRef, which requires engine FunctionCallSite support.");
        }
        FixtureRule.Behavior behavior = rule.behavior();
        if (behavior.kind() == FixtureRule.BehaviorKind.STREAM) {
            diagnostics.add(prefix + " uses reserved behavior " + behavior.kind() + ".");
        }
        if (!behavior.sequence().isEmpty()) {
            diagnostics.add(prefix + " populates reserved sequence fields.");
        }
        boolean replay = behavior.kind() == FixtureRule.BehaviorKind.REPLAY;
        if (replay) {
            validateReplayBehavior(prefix, rule, diagnostics, replayRefs);
        } else if (!behavior.replayRef().isBlank()) {
            diagnostics.add(prefix + ".behavior.replayRef is valid only for REPLAY.");
        }
        boolean timeBehavior = behavior.kind() == FixtureRule.BehaviorKind.DELAY
                || behavior.kind() == FixtureRule.BehaviorKind.TIMEOUT;
        if (timeBehavior && !logicalClockConfigured) {
            diagnostics.add(prefix + " uses " + behavior.kind() + " without fixtureBundle.logicalClock.");
        }
        if (timeBehavior && (behavior.after() == null || behavior.after().isZero()
                || behavior.after().isNegative())) {
            diagnostics.add(prefix + ".behavior.after must be a positive duration for "
                    + behavior.kind() + ".");
        }
        if (behavior.after() != null && behavior.after().compareTo(MAX_LOGICAL_ADVANCE) > 0) {
            diagnostics.add(prefix + ".behavior.after exceeds the 365-day logical-time bound.");
        }
        if (!timeBehavior && behavior.after() != null) {
            diagnostics.add(prefix + ".behavior.after is only valid for DELAY or TIMEOUT.");
        }
        if (timeBehavior && behavior.boundary() != FixtureRule.DoubleBoundary.NODE) {
            diagnostics.add(prefix + " time controls are supported only at the NODE boundary.");
        }
        if (behavior.kind() == FixtureRule.BehaviorKind.TIMEOUT
                && (behavior.value() != null || !behavior.rawBody().isBlank()
                || behavior.statusCode() != null || !behavior.headers().isEmpty())) {
            diagnostics.add(prefix + " TIMEOUT cannot carry a return or protocol response payload.");
        }
        if (behavior.kind() == FixtureRule.BehaviorKind.DELAY
                && (!behavior.rawBody().isBlank() || behavior.statusCode() != null
                || !behavior.headers().isEmpty() || !behavior.errorCode().isBlank()
                || !behavior.errorType().isBlank() || !behavior.errorMessage().isBlank())) {
            diagnostics.add(prefix + " DELAY can carry only after and a fixed return value.");
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

    private static void validateReplayBehavior(String prefix, FixtureRule rule,
                                               List<String> diagnostics, Set<String> replayRefs) {
        FixtureRule.Behavior behavior = rule.behavior();
        if (behavior.replayRef().isBlank()) {
            diagnostics.add(prefix + ".behavior.replayRef is required for REPLAY.");
        } else {
            try {
                replayRefs.add(ReplayPayloadRef.parse(behavior.replayRef()).canonical());
            } catch (IllegalArgumentException invalid) {
                diagnostics.add(prefix + ".behavior.replayRef must be an exact canonical replay reference.");
            }
        }
        if (behavior.boundary() != FixtureRule.DoubleBoundary.NODE) {
            diagnostics.add(prefix + " REPLAY is supported only at the NODE boundary.");
        }
        if (behavior.value() != null || !behavior.rawBody().isBlank()
                || behavior.statusCode() != null || !behavior.headers().isEmpty()
                || !behavior.errorCode().isBlank() || !behavior.errorType().isBlank()
                || !behavior.errorMessage().isBlank() || behavior.after() != null
                || !behavior.sequence().isEmpty()) {
            diagnostics.add(prefix + " REPLAY can carry only replayRef; payload and fault fields are forbidden.");
        }
        if (rule.consumption().onUnmatched() != FixtureRule.UnmatchedAction.FAIL
                || rule.consumption().onExhausted() != FixtureRule.ExhaustedAction.FAIL) {
            diagnostics.add(prefix + " REPLAY cannot fall back to REAL when unmatched or exhausted.");
        }
    }

    private static void validateCoordinates(String path, List<Integer> values,
                                            List<String> diagnostics) {
        if (values.size() > MAX_SELECTOR_COORDINATES) {
            diagnostics.add(path + " may contain at most " + MAX_SELECTOR_COORDINATES + " values.");
            return;
        }
        int previous = 0;
        for (int index = 0; index < values.size(); index++) {
            Integer value = values.get(index);
            if (value == null || value < 1 || value > MAX_SELECTOR_COORDINATE) {
                diagnostics.add(path + "[" + index + "] must be between 1 and "
                        + MAX_SELECTOR_COORDINATE + ".");
                continue;
            }
            if (value <= previous) {
                diagnostics.add(path + " must be strictly increasing without duplicates.");
                return;
            }
            previous = value;
        }
    }

    private static List<String> bounded(List<String> diagnostics) {
        return diagnostics.stream().limit(20).map(value -> value.length() <= 300
                ? value : value.substring(0, 300)).toList();
    }

    private static void reject(String diagnostic) {
        throw new ControlPlanRejectedException("CONTROL_PLAN_REJECTED", List.of(diagnostic));
    }
}
