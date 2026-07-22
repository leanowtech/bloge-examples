package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;

import java.util.Objects;

/**
 * Server-internal runtime companion to the payload-free cross-system {@link MirrorPlan}.
 *
 * <p>The public plan carries only exact references, fingerprints, policy, and binding metadata.
 * The compiled execution control retains frozen FixtureRule values and governed replay payloads in
 * process, preventing those values from leaking into the wire artifact.</p>
 *
 * @param plan sealed cross-system mirror plan
 * @param graph exact executable graph generation selected during compilation
 * @param fixtureBundle exact fixture generation selected during compilation
 * @param executionControl exact BLOGE operator-resolution control described by the plan
 */
public record CompiledMirrorPlan(
        MirrorPlan plan,
        Graph graph,
        FixtureBundle fixtureBundle,
        CompiledExecutionControl executionControl
) {
    /** Requires the public plan and every runtime artifact from one execution generation. */
    public CompiledMirrorPlan {
        plan = Objects.requireNonNull(plan, "plan");
        graph = Objects.requireNonNull(graph, "graph");
        fixtureBundle = Objects.requireNonNull(fixtureBundle, "fixtureBundle");
        executionControl = Objects.requireNonNull(executionControl, "executionControl");
        if (!plan.executionControlFingerprint()
                .equals(executionControl.effectivePlan().planFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror plan and execution control fingerprints must match");
        }
        if (!plan.policy().authorizedPurpose()
                .equals(executionControl.effectivePlan().authorizedPurpose())) {
            throw new IllegalArgumentException(
                    "mirror plan and execution control purposes must match");
        }
        if (!plan.fixtureBundleRef().id().equals(fixtureBundle.fixtureBundleId())
                || plan.fixtureBundleRef().revision() != fixtureBundle.revision()
                || !plan.fixtureBundleRef().fingerprint()
                .equals(executionControl.effectivePlan().fixtureBundleFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror plan, fixture, and execution control identities must match");
        }
    }
}
