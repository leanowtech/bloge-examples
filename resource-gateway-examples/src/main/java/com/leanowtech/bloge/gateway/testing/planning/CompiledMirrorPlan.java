package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;

import java.util.Objects;

/**
 * Server-internal runtime companion to the payload-free cross-system {@link MirrorPlan}.
 *
 * <p>The public plan carries only exact references, fingerprints, policy, and binding metadata.
 * The compiled execution control retains frozen FixtureRule values and governed replay payloads in
 * process, preventing those values from leaking into the wire artifact.</p>
 *
 * @param plan sealed cross-system mirror plan
 * @param executionControl exact BLOGE operator-resolution control described by the plan
 */
public record CompiledMirrorPlan(
        MirrorPlan plan,
        CompiledExecutionControl executionControl
) {
    /** Requires both public and runtime halves of one execution generation. */
    public CompiledMirrorPlan {
        plan = Objects.requireNonNull(plan, "plan");
        executionControl = Objects.requireNonNull(executionControl, "executionControl");
        if (!plan.executionControlFingerprint()
                .equals(executionControl.effectivePlan().planFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror plan and execution control fingerprints must match");
        }
    }
}
