package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Result of executing a sealed mirror generation through the independent BLOGE test engine.
 *
 * @param plan exact public plan admitted for the run
 * @param admittedAt server admission time used for TTL enforcement
 * @param execution graph result and semantically fingerprinted evidence from the shared kernel
 * @param resolutions sealed payload-free resolver provenance ordered by invocation coordinate
 * @param evidenceBundle independently verified portable payload-free run evidence
 */
public record MirrorRunResult(
        MirrorPlan plan,
        Instant admittedAt,
        TestExecutionResult execution,
        List<MirrorResolution> resolutions,
        MirrorEvidenceBundle evidenceBundle
) {
    /** Requires one complete admitted execution result. */
    public MirrorRunResult {
        plan = Objects.requireNonNull(plan, "plan");
        admittedAt = Objects.requireNonNull(admittedAt, "admittedAt");
        execution = Objects.requireNonNull(execution, "execution");
        evidenceBundle = Objects.requireNonNull(evidenceBundle, "evidenceBundle");
        resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
        Comparator<MirrorResolution> order = Comparator
                .comparing(MirrorResolution::invocationSiteId)
                .thenComparing(MirrorResolution::correlationKey)
                .thenComparingInt(MirrorResolution::occurrence)
                .thenComparingInt(MirrorResolution::attempt);
        HashSet<String> coordinates = new HashSet<>();
        MirrorResolution previous = null;
        for (MirrorResolution resolution : resolutions) {
            if (!plan.planFingerprint().equals(resolution.planFingerprint())
                    || !execution.evidence().runId().equals(resolution.runId())) {
                throw new IllegalArgumentException(
                        "mirror resolutions must belong to the exact plan and run");
            }
            String coordinate = resolution.invocationSiteId() + "\u0000"
                    + resolution.correlationKey() + "\u0000" + resolution.occurrence()
                    + "\u0000" + resolution.attempt();
            if (!coordinates.add(coordinate)) {
                throw new IllegalArgumentException(
                        "mirror resolutions must have unique invocation coordinates");
            }
            if (previous != null && order.compare(previous, resolution) > 0) {
                throw new IllegalArgumentException(
                        "mirror resolutions must be ordered by invocation coordinates");
            }
            previous = resolution;
        }
        if (!evidenceBundle.evidence().runId().equals(execution.evidence().runId())
                || !evidenceBundle.evidence().planFingerprint().equals(plan.planFingerprint())
                || !evidenceBundle.evidence().capabilityClosureFingerprint()
                .equals(plan.capabilityClosureFingerprint())
                || !evidenceBundle.evidence().executionControlFingerprint()
                .equals(plan.executionControlFingerprint())
                || !evidenceBundle.evidence().fixtureBundleRef().equals(plan.fixtureBundleRef())
                || !externalBindingsMatch(plan, evidenceBundle.evidence().externalBindings())
                || !evidenceBundle.evidence().semanticResultFingerprint()
                .equals(execution.evidence().semanticResultFingerprint())
                || !evidenceBundle.evidence().resolutions().equals(resolutions)) {
            throw new IllegalArgumentException(
                    "mirror evidence bundle must bind the exact plan, run, and resolutions");
        }
    }

    private static boolean externalBindingsMatch(
            MirrorPlan plan,
            List<MirrorRunEvidence.ExternalBinding> evidenceBindings) {
        if (plan.externalBindings().size() != evidenceBindings.size()) {
            return false;
        }
        for (int index = 0; index < evidenceBindings.size(); index++) {
            MirrorPlan.ExternalBinding expected = plan.externalBindings().get(index);
            var actual = evidenceBindings.get(index);
            if (!expected.parentCapabilityRef().equals(actual.parentCapabilityRef())
                    || !expected.dependencyNodeId().equals(actual.dependencyNodeId())
                    || !expected.capabilityRef().equals(actual.capabilityRef())
                    || !expected.invocationSiteId().equals(actual.invocationSiteId())
                    || !expected.graphPath().equals(actual.graphPath())) {
                return false;
            }
        }
        return true;
    }

    /** @return whether graph execution and all fixture assertions passed */
    public boolean passed() {
        return execution.passed();
    }
}
