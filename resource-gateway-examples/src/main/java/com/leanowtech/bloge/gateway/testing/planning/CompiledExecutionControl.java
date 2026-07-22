package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.runtime.GovernedExecutionServices;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;

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
 * @param controls resolved controls keyed by governance invocation-site id
 * @param rules frozen source rules in declaration order
 * @param inventory exact reachable runtime bindings used to compute fingerprints and resolve a run
 * @param replayPayloads exact governed values frozen before compilation
 * @param executionServices exact run-scoped services frozen during plan compilation
 */
public record CompiledExecutionControl(
        EffectiveExecutionPlan effectivePlan,
        Map<String, ResolvedControl> controls,
        List<FixtureRule> rules,
        InvocationInventory inventory,
        ResolvedReplayPayloads replayPayloads,
        GovernedExecutionServices executionServices
) {
    /** Creates immutable runtime collections. */
    public CompiledExecutionControl {
        controls = controls == null ? Map.of() : Map.copyOf(controls);
        rules = rules == null ? List.of() : List.copyOf(rules);
        inventory = inventory == null
                ? new InvocationInventory(List.of(), Map.of(), Map.of()) : inventory;
        replayPayloads = replayPayloads == null ? ResolvedReplayPayloads.empty() : replayPayloads;
        executionServices = java.util.Objects.requireNonNull(executionServices, "executionServices");
    }

    /**
     * Runtime resolution for a node. Rules are ordered by descending selector specificity.
     * Same-precedence rules coexist only when preflight proves a dynamic coordinate or input
     * constraint makes them disjoint; lower-precedence rules remain available as explicit fallback.
     *
     * @param site stable invocation site
     * @param rules ordered candidate rules
     * @param implicitDeny whether the control was synthesized by side-effect fail-closed policy
     */
    public record ResolvedControl(
            InvocationSite site,
            List<FixtureRule> rules,
            boolean implicitDeny,
            ResolutionStrategy resolutionStrategy,
            List<MirrorPlan.MirrorSource> resolverOrder
    ) {
        /** Rule-selection strategy frozen into the execution generation. */
        public enum ResolutionStrategy {
            SELECTOR_SPECIFICITY,
            MIRROR_SOURCE_THEN_SELECTOR
        }

        /** Creates an immutable candidate list. */
        public ResolvedControl {
            site = java.util.Objects.requireNonNull(site, "site");
            rules = rules == null ? List.of() : List.copyOf(rules);
            resolutionStrategy = resolutionStrategy == null
                    ? ResolutionStrategy.SELECTOR_SPECIFICITY : resolutionStrategy;
            resolverOrder = resolverOrder == null ? List.of() : List.copyOf(resolverOrder);
            if (resolutionStrategy == ResolutionStrategy.SELECTOR_SPECIFICITY
                    && !resolverOrder.isEmpty()) {
                throw new IllegalArgumentException(
                        "ordinary controls must not carry mirror resolver order");
            }
            if (resolutionStrategy == ResolutionStrategy.MIRROR_SOURCE_THEN_SELECTOR
                    && (resolverOrder.isEmpty()
                    || resolverOrder.getLast() != MirrorPlan.MirrorSource.ABSTAINED)) {
                throw new IllegalArgumentException(
                        "mirror controls require resolver order ending in ABSTAINED");
            }
        }

        /** Backward-compatible ordinary selector-specificity control. */
        public ResolvedControl(InvocationSite site, List<FixtureRule> rules, boolean implicitDeny) {
            this(site, rules, implicitDeny, ResolutionStrategy.SELECTOR_SPECIFICITY, List.of());
        }

        /**
         * Creates an external mirror control whose source precedence is fixed before selector
         * specificity is considered.
         *
         * @param site exact invocation site
         * @param rules candidate owner and governed replay rules
         * @param implicitDeny whether no source was configured
         * @return immutable mirror control
         */
        public static ResolvedControl mirror(
                InvocationSite site, List<FixtureRule> rules, boolean implicitDeny) {
            java.util.LinkedHashSet<MirrorPlan.MirrorSource> sources =
                    new java.util.LinkedHashSet<>();
            if (!implicitDeny && rules != null) {
                rules.forEach(rule -> sources.add(rule.behavior().kind()
                        == FixtureRule.BehaviorKind.REPLAY
                        ? MirrorPlan.MirrorSource.GOVERNED_REPLAY
                        : MirrorPlan.MirrorSource.OWNER_SPECIFIED));
            }
            List<MirrorPlan.MirrorSource> order = new java.util.ArrayList<>(sources);
            order.sort(java.util.Comparator.naturalOrder());
            order.add(MirrorPlan.MirrorSource.ABSTAINED);
            return new ResolvedControl(site, rules, implicitDeny,
                    ResolutionStrategy.MIRROR_SOURCE_THEN_SELECTOR, order);
        }
    }
}
