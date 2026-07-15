package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.List;
import java.util.Map;

/**
 * Server-internal executable companion to the public, immutable effective-plan projection.
 *
 * <p>Wire contracts deliberately do not embed fixture payloads in
 * {@link EffectiveExecutionPlan}. This type keeps the already-frozen rule references available to
 * the runtime without rereading mutable fixture storage during execution.</p>
 *
 * @param effectivePlan payload-free auditable plan projection
 * @param controls resolved node controls keyed by root graph node id
 * @param rules frozen source rules in declaration order
 * @param frozenOperators exact root-node runtime bindings used to compute binding fingerprints
 */
public record CompiledExecutionControl(
        EffectiveExecutionPlan effectivePlan,
        Map<String, ResolvedControl> controls,
        List<FixtureRule> rules,
        Map<String, Object> frozenOperators
) {
    /** Creates immutable runtime collections. */
    public CompiledExecutionControl {
        controls = controls == null ? Map.of() : Map.copyOf(controls);
        rules = rules == null ? List.of() : List.copyOf(rules);
        frozenOperators = frozenOperators == null ? Map.of() : Map.copyOf(frozenOperators);
    }

    /**
     * Runtime resolution for a node. Multiple rules are allowed only when preflight proves their
     * resource or canonical path constraints are disjoint.
     *
     * @param site stable invocation site
     * @param rules ordered candidate rules
     * @param implicitDeny whether the control was synthesized by side-effect fail-closed policy
     */
    public record ResolvedControl(InvocationSite site, List<FixtureRule> rules, boolean implicitDeny) {
        /** Creates an immutable candidate list. */
        public ResolvedControl {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
