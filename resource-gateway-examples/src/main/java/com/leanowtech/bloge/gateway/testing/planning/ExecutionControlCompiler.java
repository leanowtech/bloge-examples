package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Compiles caller fixture intent into a deterministic, fail-closed execution-control plan.
 *
 * <p>The compiler is the sole point at which selectors, target bindings, activation boundaries,
 * and default side-effect policy are combined. Runtime code receives a frozen
 * {@link CompiledExecutionControl}; it never consults mutable fixture repositories.</p>
 */
public class ExecutionControlCompiler {

    private final OperatorRegistry registry;
    private final ObjectMapper objectMapper;
    private final SafetyPreflight safetyPreflight;
    private final SelectorResolver selectorResolver;

    /**
     * @param registry frozen operator binding inventory used by the independent test engine
     * @param objectMapper JSON mapper used for canonical protocol fingerprints
     */
    public ExecutionControlCompiler(OperatorRegistry registry, ObjectMapper objectMapper) {
        this(registry, objectMapper, new SafetyPreflight(), new SelectorResolver());
    }

    /** Constructor for focused tests and policy extension. */
    public ExecutionControlCompiler(OperatorRegistry registry, ObjectMapper objectMapper,
                                    SafetyPreflight safetyPreflight, SelectorResolver selectorResolver) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.safetyPreflight = Objects.requireNonNull(safetyPreflight, "safetyPreflight");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
    }

    /**
     * Compiles one plan before any graph execution starts.
     *
     * @param graph frozen graph artifact
     * @param fixtureBundle frozen fixture bundle
     * @param authorizedPurpose server-minted purpose, never copied from business context
     * @param targetFingerprint selected artifact fingerprint
     * @return executable and auditable frozen plan
     * @throws ControlPlanRejectedException for invalid, zero-match, or ambiguous controls
     */
    public CompiledExecutionControl compile(Graph graph, FixtureBundle fixtureBundle,
                                            String authorizedPurpose, String targetFingerprint) {
        Objects.requireNonNull(graph, "graph");
        safetyPreflight.validate(fixtureBundle, authorizedPurpose, targetFingerprint);

        Map<String, Object> frozenOperators = freezeOperators(graph);
        Map<String, String> bindingFingerprints = bindingFingerprints(graph, frozenOperators);
        Map<String, CompiledExecutionControl.ResolvedControl> controls = new LinkedHashMap<>(
                selectorResolver.resolve(graph, targetFingerprint, bindingFingerprints, fixtureBundle.rules()));

        for (NodeSpec node : graph.nodes().values()) {
            if (!controls.containsKey(node.id())
                    && externalEffect(node, frozenOperators.get(node.id()))) {
                InvocationSite site = selectorResolver.site(node, targetFingerprint,
                        bindingFingerprints.get(node.id()));
                controls.put(node.id(), new CompiledExecutionControl.ResolvedControl(
                        site, List.of(implicitDeny(node)), true));
            }
        }
        rejectUnsafeExternalReal(graph, controls, frozenOperators);

        String fixtureFingerprint = ProtocolFingerprint.of(objectMapper, fixtureBundle);
        List<EffectiveExecutionPlan.ResolvedSite> sites = new ArrayList<>();
        for (NodeSpec node : graph.nodes().values()) {
            CompiledExecutionControl.ResolvedControl control = controls.get(node.id());
            if (control == null) {
                InvocationSite site = selectorResolver.site(node, targetFingerprint,
                        bindingFingerprints.get(node.id()));
                sites.add(new EffectiveExecutionPlan.ResolvedSite(site.invocationSiteId(),
                        EffectiveExecutionPlan.Resolution.REAL, FixtureRule.BehaviorKind.REAL,
                        FixtureRule.DoubleBoundary.NODE, List.of(), "REAL"));
            } else {
                FixtureRule first = control.rules().getFirst();
                FixtureRule.BehaviorKind kind = first.behavior().kind();
                sites.add(new EffectiveExecutionPlan.ResolvedSite(control.site().invocationSiteId(),
                        resolution(control), kind, first.behavior().boundary(),
                        control.rules().stream().map(FixtureRule::ruleId).toList(), fidelity(control)));
            }
        }
        Map<String, String> defaults = Map.of(
                "externalEffects", "DENY",
                "selectorZeroMatch", "FAIL",
                "selectorAmbiguity", "FAIL",
                "productionControl", "REJECT");
        Map<String, Object> fingerprintMaterial = Map.of(
                "purpose", authorizedPurpose,
                "target", targetFingerprint,
                "fixture", fixtureFingerprint,
                "sites", sites,
                "defaults", defaults);
        String planFingerprint = ProtocolFingerprint.of(objectMapper, fingerprintMaterial);
        EffectiveExecutionPlan effectivePlan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION,
                "plan-" + UUID.randomUUID(),
                planFingerprint,
                authorizedPurpose,
                targetFingerprint,
                fixtureFingerprint,
                sites,
                defaults,
                List.of());
        return new CompiledExecutionControl(effectivePlan, controls, fixtureBundle.rules(), frozenOperators);
    }

    /** Resolves the real operator using the same node-id, embedded-ref, registry ordering as BLOGE. */
    private Object resolveRealOperator(Graph graph, NodeSpec node) {
        if (graph.embeddedOperators().containsKey(node.id())) {
            return graph.embeddedOperators().get(node.id());
        }
        if (graph.embeddedOperators().containsKey(node.operatorRef())) {
            return graph.embeddedOperators().get(node.operatorRef());
        }
        return registry.lookup(node.operatorRef());
    }

    private Map<String, Object> freezeOperators(Graph graph) {
        Map<String, Object> result = new LinkedHashMap<>();
        graph.nodes().values().forEach(node -> result.put(node.id(), resolveRealOperator(graph, node)));
        return Map.copyOf(result);
    }

    private Map<String, String> bindingFingerprints(Graph graph,
                                                    Map<String, Object> frozenOperators) {
        Map<String, String> result = new LinkedHashMap<>();
        graph.nodes().values().forEach(node -> {
            if (node.operatorFingerprint() != null) {
                result.put(node.id(), node.operatorFingerprint());
                return;
            }
            Object operator = frozenOperators.get(node.id());
            result.put(node.id(), ProtocolFingerprint.ofText(
                    node.operatorRef() + "|" + operator.getClass().getName() + "|"
                            + node.inputSchema().describe() + "|" + node.outputSchema().describe()));
        });
        return Map.copyOf(result);
    }

    private boolean externalEffect(NodeSpec node, Object raw) {
        if (node.metadata().kind() != null) {
            return false;
        }
        if ("httpResource".equals(node.operatorRef())) {
            return true;
        }
        return raw instanceof Operator<?, ?> operator
                && operator.sideEffectType() != SideEffectType.READ_ONLY;
    }

    private void rejectUnsafeExternalReal(
            Graph graph, Map<String, CompiledExecutionControl.ResolvedControl> controls,
            Map<String, Object> frozenOperators) {
        controls.forEach((nodeId, control) -> {
            NodeSpec node = graph.nodes().get(nodeId);
            if (control.implicitDeny() || !externalEffect(node, frozenOperators.get(nodeId))) {
                return;
            }
            boolean unsafe = control.rules().stream().anyMatch(rule ->
                    SetLike.REAL.contains(rule.behavior().kind())
                            || rule.consumption().onUnmatched() == FixtureRule.UnmatchedAction.ALLOW_REAL
                            || rule.consumption().onExhausted() == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL);
            if (unsafe) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_UNSAFE_EXTERNAL_REAL", List.of(
                        "External-effect site '/root/" + nodeId
                                + "' cannot use REAL/SPY or a fallback-to-real policy in v1."));
            }
        });
    }

    private static FixtureRule implicitDeny(NodeSpec node) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, "implicit-deny:" + node.id(),
                FixtureRule.Selector.node(node.id()),
                FixtureRule.Behavior.throwing("FIXTURE_UNMATCHED", "UNCONTROLLED_EXTERNAL_EFFECT",
                        "External-effect invocation has no matching fixture."),
                FixtureRule.Consumption.optionalOnce(), FixtureRule.SchemaCheck.strict());
    }

    private static EffectiveExecutionPlan.Resolution resolution(
            CompiledExecutionControl.ResolvedControl control) {
        if (control.implicitDeny()
                || control.rules().stream().allMatch(rule -> rule.behavior().kind()
                == FixtureRule.BehaviorKind.DENY)) {
            return EffectiveExecutionPlan.Resolution.DENIED;
        }
        if (control.rules().stream().allMatch(rule -> SetLike.REAL.contains(rule.behavior().kind()))) {
            return EffectiveExecutionPlan.Resolution.REAL;
        }
        return EffectiveExecutionPlan.Resolution.TEST_DOUBLE;
    }

    private static String fidelity(CompiledExecutionControl.ResolvedControl control) {
        FixtureRule.Behavior behavior = control.rules().getFirst().behavior();
        if (behavior.kind() == FixtureRule.BehaviorKind.REAL
                || behavior.kind() == FixtureRule.BehaviorKind.SPY) {
            return "REAL";
        }
        if (behavior.statusCode() != null && behavior.value() == null) {
            return behavior.boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                    ? "TRANSPORT_LEVEL" : "PROTOCOL_DERIVED";
        }
        return "OUTPUT_LEVEL";
    }

    private static final class SetLike {
        private static final List<FixtureRule.BehaviorKind> REAL = List.of(
                FixtureRule.BehaviorKind.REAL, FixtureRule.BehaviorKind.SPY);

        private SetLike() {
        }
    }
}
