package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;

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
 */
public record MirrorRunResult(
        MirrorPlan plan,
        Instant admittedAt,
        TestExecutionResult execution,
        List<MirrorResolution> resolutions
) {
    /** Requires one complete admitted execution result. */
    public MirrorRunResult {
        plan = Objects.requireNonNull(plan, "plan");
        admittedAt = Objects.requireNonNull(admittedAt, "admittedAt");
        execution = Objects.requireNonNull(execution, "execution");
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
    }

    /** Backward-compatible result without resolver provenance. */
    public MirrorRunResult(
            MirrorPlan plan, Instant admittedAt, TestExecutionResult execution) {
        this(plan, admittedAt, execution, List.of());
    }

    /** @return whether graph execution and all fixture assertions passed */
    public boolean passed() {
        return execution.passed();
    }
}
