package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.engine.operators.ParallelSubGraphOperator;
import com.leanowtech.bloge.core.engine.operators.StreamingForEachOperator;
import com.leanowtech.bloge.core.engine.operators.StreamingLoopOperator;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.GovernedExecutionServices;

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

    private final ObjectMapper objectMapper;
    private final SafetyPreflight safetyPreflight;
    private final SelectorResolver selectorResolver;
    private final InvocationInventoryBuilder inventoryBuilder;

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
        Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.safetyPreflight = Objects.requireNonNull(safetyPreflight, "safetyPreflight");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
        this.inventoryBuilder = new InvocationInventoryBuilder(registry);
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
        return compile(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                ResolvedReplayPayloads.empty());
    }

    /**
     * Compiles a plan whose governed replay dependencies were resolved before planner entry.
     *
     * @param graph frozen graph artifact
     * @param fixtureBundle frozen fixture bundle
     * @param authorizedPurpose server-minted purpose
     * @param targetFingerprint selected artifact fingerprint
     * @param replayPayloads exact run-scoped replay values
     * @return executable and auditable frozen plan
     */
    public CompiledExecutionControl compile(Graph graph, FixtureBundle fixtureBundle,
                                            String authorizedPurpose, String targetFingerprint,
                                            ResolvedReplayPayloads replayPayloads) {
        return compile(graph, fixtureBundle, authorizedPurpose, targetFingerprint, replayPayloads,
                null);
    }

    /**
     * Recompiles an exact plan and restores its content-addressed execution-service checkpoint.
     *
     * <p>Normal preflight always runs before restore. A checkpoint never supplies a plan, fixture or
     * provider configuration; it can only continue state after the independently rebuilt plan has
     * the same fingerprint and binding set.</p>
     *
     * @param graph frozen graph artifact
     * @param fixtureBundle exact immutable fixture used before suspension
     * @param authorizedPurpose server-minted purpose
     * @param targetFingerprint selected artifact fingerprint
     * @param replayPayloads exact run-scoped replay values
     * @param providerState payload-free execution-service checkpoint, or {@code null} for a fresh run
     * @return executable plan with fresh or restored run-scoped services
     * @throws ControlPlanRejectedException with {@code CONTROL_PLAN_UNAVAILABLE} when restore fails
     */
    public CompiledExecutionControl compile(Graph graph, FixtureBundle fixtureBundle,
                                            String authorizedPurpose, String targetFingerprint,
                                            ResolvedReplayPayloads replayPayloads,
                                            ExecutionServiceStateSnapshot providerState) {
        return compileBound(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                targetFingerprint, replayPayloads, providerState);
    }

    /**
     * Compiles a mutation child while retaining the fixture's exact baseline target binding.
     *
     * <p>This is an internal test-runtime capability, not a general target-retargeting API. It is
     * accepted only for the server-minted mutation-suite purpose and only when the execution target
     * differs from the fixture-binding target. Selectors and invocation inventory are compiled from
     * the mutant graph; the baseline target is used solely for fixture provenance validation and is
     * included in the resulting plan fingerprint.</p>
     *
     * @param graph exact server-regenerated mutant graph
     * @param fixtureBundle immutable stored fixture bound to the reviewed baseline
     * @param authorizedPurpose server-minted mutation-suite purpose
     * @param executionTargetFingerprint exact mutant target fingerprint used by plan and evidence
     * @param fixtureBindingTargetFingerprint exact reviewed baseline target fingerprint
     * @param replayPayloads exact run-scoped replay values
     * @return executable and auditable frozen mutant plan
     * @throws ControlPlanRejectedException when the mutation-only boundary is misused
     */
    public CompiledExecutionControl compileMutation(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String executionTargetFingerprint,
            String fixtureBindingTargetFingerprint,
            ResolvedReplayPayloads replayPayloads) {
        if (!TestSuiteRunEvidenceV5.EXECUTION_PURPOSE.equals(authorizedPurpose)
                || Objects.equals(executionTargetFingerprint, fixtureBindingTargetFingerprint)) {
            throw new ControlPlanRejectedException("CONTROL_PLAN_MUTATION_BINDING_INVALID", List.of(
                    "Separate fixture binding is restricted to a distinct mutation-suite target."));
        }
        return compileBound(graph, fixtureBundle, authorizedPurpose, executionTargetFingerprint,
                fixtureBindingTargetFingerprint, replayPayloads, null);
    }

    private CompiledExecutionControl compileBound(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            String fixtureBindingTargetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            ExecutionServiceStateSnapshot providerState) {
        Objects.requireNonNull(graph, "graph");
        ResolvedReplayPayloads resolvedReplays = replayPayloads == null
                ? ResolvedReplayPayloads.empty() : replayPayloads;
        safetyPreflight.validate(fixtureBundle, authorizedPurpose,
                fixtureBindingTargetFingerprint, resolvedReplays);

        InvocationInventory inventory = inventoryBuilder.build(graph, targetFingerprint);
        GovernedExecutionServices executionServices = GovernedExecutionServices.prepare(
                objectMapper, fixtureBundle, inventory);
        Map<String, CompiledExecutionControl.ResolvedControl> controls = new LinkedHashMap<>(
                selectorResolver.resolve(inventory, fixtureBundle.rules()));

        for (InvocationInventory.Entry entry : inventory.entries()) {
            String siteId = entry.site().invocationSiteId();
            if (!controls.containsKey(siteId) && externalEffect(entry)) {
                controls.put(siteId, new CompiledExecutionControl.ResolvedControl(
                        entry.site(), List.of(implicitDeny(entry)), true));
            }
        }
        rejectUnsupportedControlledBindings(inventory, controls);
        rejectUnsafeExternalReal(inventory, controls);

        String fixtureFingerprint = ProtocolFingerprint.of(objectMapper, fixtureBundle);
        List<EffectiveExecutionPlan.ResolvedSite> sites = new ArrayList<>();
        for (InvocationInventory.Entry entry : inventory.entries()) {
            CompiledExecutionControl.ResolvedControl control =
                    controls.get(entry.site().invocationSiteId());
            if (control == null) {
                sites.add(new EffectiveExecutionPlan.ResolvedSite(entry.site().invocationSiteId(),
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
        Map<String, Object> fingerprintMaterial = new LinkedHashMap<>();
        fingerprintMaterial.put("purpose", authorizedPurpose);
        fingerprintMaterial.put("target", targetFingerprint);
        fingerprintMaterial.put("fixture", fixtureFingerprint);
        fingerprintMaterial.put("inventory", inventory.entries().stream().map(entry -> Map.of(
                "engineStructuralId", entry.engineStructuralId(),
                "invocationSiteId", entry.site().invocationSiteId(),
                "bindingFingerprint", entry.site().runtimeBindingFingerprint())).toList());
        fingerprintMaterial.put("sites", sites);
        fingerprintMaterial.put("replayDependencies", resolvedReplays.planDependencies());
        fingerprintMaterial.put("executionServiceBindings", executionServices.bindings());
        fingerprintMaterial.put("defaults", defaults);
        if (!Objects.equals(targetFingerprint, fixtureBindingTargetFingerprint)) {
            fingerprintMaterial.put("fixtureBindingTarget", fixtureBindingTargetFingerprint);
        }
        String planFingerprint = ProtocolFingerprint.of(objectMapper, fingerprintMaterial);
        if (providerState == null) {
            executionServices.bindToPlan(planFingerprint);
        } else {
            try {
                executionServices = GovernedExecutionServices.restore(objectMapper, fixtureBundle,
                        inventory, planFingerprint, providerState);
            } catch (IllegalArgumentException unavailable) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_UNAVAILABLE", List.of(
                        "Checkpointed execution-service state does not match the frozen plan."));
            }
        }
        EffectiveExecutionPlan effectivePlan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION,
                "plan-" + UUID.randomUUID(),
                planFingerprint,
                authorizedPurpose,
                targetFingerprint,
                fixtureFingerprint,
                sites,
                resolvedReplays.planDependencies(),
                executionServices.bindings(),
                defaults,
                List.of());
        return new CompiledExecutionControl(effectivePlan, controls, fixtureBundle.rules(), inventory,
                resolvedReplays, executionServices);
    }

    private boolean externalEffect(InvocationInventory.Entry entry) {
        var node = entry.node();
        if (node.metadata().kind() != null
                && entry.site().invocationKind() != InvocationSite.InvocationKind.COMPENSATION) {
            return false;
        }
        if ("httpResource".equals(node.operatorRef())) {
            return true;
        }
        if (isBuiltInNestedContainer(entry.frozenOperator())) {
            return false;
        }
        return entry.frozenOperator() instanceof Operator<?, ?> operator
                && operator.sideEffectType() != SideEffectType.READ_ONLY;
    }

    private static boolean isBuiltInNestedContainer(Object operator) {
        return operator instanceof SubGraphOperator
                || operator instanceof ForEachOperator
                || operator instanceof LoopOperator
                || operator instanceof ParallelSubGraphOperator
                || operator instanceof StreamingForEachOperator
                || operator instanceof StreamingLoopOperator;
    }

    private static void rejectUnsupportedControlledBindings(
            InvocationInventory inventory,
            Map<String, CompiledExecutionControl.ResolvedControl> controls) {
        controls.forEach((siteId, control) -> {
            InvocationInventory.Entry entry = inventory.byInvocationSiteId().get(siteId);
            if (entry != null && !(entry.frozenOperator() instanceof Operator<?, ?>)) {
                throw new ControlPlanRejectedException(
                        "CONTROL_PLAN_UNSUPPORTED_OPERATOR_TYPE", List.of(
                        "Invocation site '" + siteId
                                + "' is not a synchronous Operator and cannot be controlled by v1."));
            }
        });
    }

    private void rejectUnsafeExternalReal(
            InvocationInventory inventory,
            Map<String, CompiledExecutionControl.ResolvedControl> controls) {
        controls.forEach((siteId, control) -> {
            InvocationInventory.Entry entry = inventory.byInvocationSiteId().get(siteId);
            if (entry == null || control.implicitDeny() || !externalEffect(entry)) {
                return;
            }
            boolean unsafe = control.rules().stream().anyMatch(rule ->
                    SetLike.REAL.contains(rule.behavior().kind())
                            || rule.consumption().onUnmatched() == FixtureRule.UnmatchedAction.ALLOW_REAL
                            || rule.consumption().onExhausted() == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL);
            if (unsafe) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_UNSAFE_EXTERNAL_REAL", List.of(
                        "External-effect site '" + siteId
                                + "' cannot use REAL/SPY or a fallback-to-real policy in v1."));
            }
        });
    }

    private static FixtureRule implicitDeny(InvocationInventory.Entry entry) {
        InvocationSite site = entry.site();
        FixtureRule.Selector selector = new FixtureRule.Selector(
                site.graphPath(), site.nodeId(), site.operatorRef(), "", "",
                List.of(), List.of(), site.invocationKind(), List.of(), List.of(), "",
                FixtureRule.Match.none());
        return new FixtureRule(FixtureRule.SCHEMA_VERSION,
                "implicit-deny:" + site.invocationSiteId(), selector,
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
        if (behavior.kind() == FixtureRule.BehaviorKind.REPLAY) {
            return "REPLAYED";
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
