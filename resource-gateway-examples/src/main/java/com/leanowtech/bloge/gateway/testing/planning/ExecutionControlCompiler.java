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
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.GovernedExecutionServices;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
                targetFingerprint, replayPayloads, ResolvedTestSecrets.empty(), providerState);
    }

    /**
     * Compiles with fresh externally authorized run-scoped test-secret values.
     *
     * @param graph frozen graph artifact
     * @param fixtureBundle exact immutable fixture
     * @param authorizedPurpose server-minted purpose
     * @param targetFingerprint selected artifact fingerprint
     * @param replayPayloads exact run-scoped replay values
     * @param testSecrets exact run-scoped secret values
     * @return executable and auditable frozen plan
     */
    public CompiledExecutionControl compileWithSecrets(
            Graph graph, FixtureBundle fixtureBundle, String authorizedPurpose,
            String targetFingerprint, ResolvedReplayPayloads replayPayloads,
            ResolvedTestSecrets testSecrets) {
        return compileWithSecrets(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                replayPayloads, testSecrets, null);
    }

    /**
     * Recompiles and restores provider state after fresh test-secret re-authorization.
     *
     * @param graph frozen graph artifact
     * @param fixtureBundle exact immutable fixture
     * @param authorizedPurpose server-minted purpose
     * @param targetFingerprint selected artifact fingerprint
     * @param replayPayloads freshly resolved replay closure
     * @param testSecrets freshly resolved secret closure
     * @param providerState payload-free provider checkpoint, or null
     * @return executable fresh or restored control
     */
    public CompiledExecutionControl compileWithSecrets(
            Graph graph, FixtureBundle fixtureBundle, String authorizedPurpose,
            String targetFingerprint, ResolvedReplayPayloads replayPayloads,
            ResolvedTestSecrets testSecrets, ExecutionServiceStateSnapshot providerState) {
        return compileBound(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                targetFingerprint, replayPayloads, testSecrets, providerState);
    }

    /**
     * Compiles a mirror execution while forcing every capability-derived external site through the
     * existing fixture-control runtime.
     *
     * <p>Sites without an owner rule receive the same implicit denial used for other external
     * effects. REAL, SPY, unmatched-real, and exhausted-real policies are rejected even when the
     * frozen operator reports itself as read-only. This method is the adapter boundary that lets
     * MirrorPlan reuse FixtureBundle instead of defining a parallel fixture language.</p>
     *
     * @param graph exact graph artifact
     * @param fixtureBundle exact existing fixture bundle
     * @param authorizedPurpose server-minted mirror purpose
     * @param targetFingerprint exact graph artifact fingerprint
     * @param replayPayloads governed replay values frozen before compilation
     * @param mandatoryExternalSiteIds capability-derived invocation sites that must be intercepted
     * @return executable frozen control shared by mirror plan and runtime
     */
    public CompiledExecutionControl compileMirror(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            Set<String> mandatoryExternalSiteIds) {
        return compileBound(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                targetFingerprint, replayPayloads, ResolvedTestSecrets.empty(), null,
                mandatoryExternalSiteIds);
    }

    /**
     * Compiles mirror controls from the exact inventory already used for capability-edge binding.
     * Package visibility keeps this authority inside the planning boundary and removes a second
     * mutable registry read between MirrorPlan binding and runtime control compilation.
     */
    CompiledExecutionControl compileMirrorFromInventory(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            Set<String> mandatoryExternalSiteIds,
            InvocationInventory frozenInventory) {
        return compileMirrorFromInventory(graph, fixtureBundle, authorizedPurpose,
                targetFingerprint, replayPayloads, mandatoryExternalSiteIds,
                frozenInventory, ResolvedCorpusPayloads.empty());
    }

    /**
     * Compiles mirror controls with an already-authorized, site-bound recorded corpus snapshot.
     *
     * <p>Standalone exact samples, reviewed retry trajectories, and validated recorded clusters
     * remain separate source indexes; the compiler freezes each available source explicitly into
     * the plan.</p>
     */
    CompiledExecutionControl compileMirrorFromInventory(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            Set<String> mandatoryExternalSiteIds,
            InvocationInventory frozenInventory,
            ResolvedCorpusPayloads corpusPayloads) {
        return compileBound(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                targetFingerprint, replayPayloads, ResolvedTestSecrets.empty(), null,
                mandatoryExternalSiteIds, Objects.requireNonNull(frozenInventory, "frozenInventory"),
                corpusPayloads);
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
                fixtureBindingTargetFingerprint, replayPayloads, ResolvedTestSecrets.empty(), null);
    }

    /**
     * Compiles one mutation child with a freshly authorized test-secret closure.
     *
     * @param graph exact server-regenerated mutant graph
     * @param fixtureBundle immutable stored fixture bound to the reviewed baseline
     * @param authorizedPurpose server-minted mutation-suite purpose
     * @param executionTargetFingerprint exact mutant target fingerprint
     * @param fixtureBindingTargetFingerprint exact reviewed baseline target fingerprint
     * @param replayPayloads exact run-scoped replay values
     * @param testSecrets exact run-scoped test-secret values
     * @return executable and auditable frozen mutant plan
     */
    public CompiledExecutionControl compileMutationWithSecrets(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String executionTargetFingerprint,
            String fixtureBindingTargetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            ResolvedTestSecrets testSecrets) {
        if (!TestSuiteRunEvidenceV5.EXECUTION_PURPOSE.equals(authorizedPurpose)
                || Objects.equals(executionTargetFingerprint, fixtureBindingTargetFingerprint)) {
            throw new ControlPlanRejectedException("CONTROL_PLAN_MUTATION_BINDING_INVALID", List.of(
                    "Separate fixture binding is restricted to a distinct mutation-suite target."));
        }
        return compileBound(graph, fixtureBundle, authorizedPurpose, executionTargetFingerprint,
                fixtureBindingTargetFingerprint, replayPayloads, testSecrets, null);
    }

    private CompiledExecutionControl compileBound(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            String fixtureBindingTargetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            ResolvedTestSecrets testSecrets,
            ExecutionServiceStateSnapshot providerState) {
        return compileBound(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureBindingTargetFingerprint, replayPayloads, testSecrets, providerState,
                null, null, ResolvedCorpusPayloads.empty());
    }

    private CompiledExecutionControl compileBound(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            String fixtureBindingTargetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            ResolvedTestSecrets testSecrets,
            ExecutionServiceStateSnapshot providerState,
            Set<String> mandatoryExternalSiteIds) {
        return compileBound(graph, fixtureBundle, authorizedPurpose, targetFingerprint,
                fixtureBindingTargetFingerprint, replayPayloads, testSecrets, providerState,
                mandatoryExternalSiteIds, null, ResolvedCorpusPayloads.empty());
    }

    private CompiledExecutionControl compileBound(
            Graph graph,
            FixtureBundle fixtureBundle,
            String authorizedPurpose,
            String targetFingerprint,
            String fixtureBindingTargetFingerprint,
            ResolvedReplayPayloads replayPayloads,
            ResolvedTestSecrets testSecrets,
            ExecutionServiceStateSnapshot providerState,
            Set<String> mandatoryExternalSiteIds,
            InvocationInventory frozenInventory,
            ResolvedCorpusPayloads corpusPayloads) {
        Objects.requireNonNull(graph, "graph");
        ResolvedReplayPayloads resolvedReplays = replayPayloads == null
                ? ResolvedReplayPayloads.empty() : replayPayloads;
        ResolvedTestSecrets resolvedSecrets = testSecrets == null
                ? ResolvedTestSecrets.empty() : testSecrets;
        ResolvedCorpusPayloads resolvedCorpus = corpusPayloads == null
                ? ResolvedCorpusPayloads.empty() : corpusPayloads;
        safetyPreflight.validate(fixtureBundle, authorizedPurpose,
                fixtureBindingTargetFingerprint, resolvedReplays);

        InvocationInventory inventory = frozenInventory == null
                ? inventoryBuilder.build(graph, targetFingerprint) : frozenInventory;
        boolean mirrorCompilation = mandatoryExternalSiteIds != null;
        Set<String> mandatoryExternalSites = normalizedSites(mandatoryExternalSiteIds);
        Set<String> recordedCorpusSites =
                normalizedSites(resolvedCorpus.siteIds());
        Set<String> recordedExactSites =
                normalizedSites(resolvedCorpus.exactSiteIds());
        Set<String> recordedTrajectorySites =
                normalizedSites(resolvedCorpus.trajectorySiteIds());
        Set<String> recordedClusterSites =
                normalizedSites(resolvedCorpus.clusterSiteIds());
        if (!mandatoryExternalSites.containsAll(recordedCorpusSites)) {
            throw new ControlPlanRejectedException(
                    "CONTROL_PLAN_CORPUS_SITE_NOT_EXTERNAL", List.of(
                    "Recorded corpus sites must belong to the external capability closure."));
        }
        List<String> missingMirrorSites = mandatoryExternalSites.stream()
                .filter(siteId -> !inventory.byInvocationSiteId().containsKey(siteId)).toList();
        if (!missingMirrorSites.isEmpty()) {
            throw new ControlPlanRejectedException("CONTROL_PLAN_MIRROR_SITE_UNRESOLVED", List.of(
                    "Capability-derived mirror invocation sites are absent from the frozen graph: "
                            + missingMirrorSites));
        }
        rejectTrajectoryRetryIncompatibility(
                inventory, resolvedCorpus, recordedTrajectorySites);
        GovernedExecutionServices executionServices = GovernedExecutionServices.prepare(
                objectMapper, fixtureBundle, inventory, resolvedSecrets);
        Map<String, CompiledExecutionControl.ResolvedControl> controls = new LinkedHashMap<>(
                mirrorCompilation
                        ? selectorResolver.resolveMirror(inventory, fixtureBundle.rules())
                        : selectorResolver.resolve(inventory, fixtureBundle.rules()));
        rejectMirrorInternalControls(controls, mandatoryExternalSites, mirrorCompilation);

        for (InvocationInventory.Entry entry : inventory.entries()) {
            String siteId = entry.site().invocationSiteId();
            if (!controls.containsKey(siteId)
                    && (externalEffect(entry) || mandatoryExternalSites.contains(siteId))) {
                List<FixtureRule> denyRules = List.of(implicitDeny(entry));
                controls.put(siteId, mirrorCompilation
                        ? CompiledExecutionControl.ResolvedControl.mirror(
                        entry.site(), denyRules, true)
                        : new CompiledExecutionControl.ResolvedControl(
                        entry.site(), denyRules, true));
            }
        }
        recordedExactSites.forEach(siteId -> controls.compute(siteId, (ignored, control) -> {
            if (control == null) {
                throw new ControlPlanRejectedException(
                        "CONTROL_PLAN_CORPUS_SITE_UNRESOLVED", List.of(
                        "Recorded exact corpus site has no mirror control."));
            }
            return control.withMirrorSource(MirrorPlan.MirrorSource.RECORDED_EXACT);
        }));
        recordedTrajectorySites.forEach(
                siteId -> controls.compute(siteId, (ignored, control) -> {
                    if (control == null) {
                        throw new ControlPlanRejectedException(
                                "CONTROL_PLAN_CORPUS_SITE_UNRESOLVED", List.of(
                                "Recorded trajectory corpus site has no mirror control."));
                    }
                    return control.withMirrorSource(
                            MirrorPlan.MirrorSource.RECORDED_TRAJECTORY);
                }));
        recordedClusterSites.forEach(
                siteId -> controls.compute(siteId, (ignored, control) -> {
                    if (control == null) {
                        throw new ControlPlanRejectedException(
                                "CONTROL_PLAN_CORPUS_SITE_UNRESOLVED", List.of(
                                "Recorded cluster corpus site has no mirror control."));
                    }
                    return control.withMirrorSource(
                            MirrorPlan.MirrorSource.RECORDED_CLUSTER);
                }));
        rejectUnsupportedControlledBindings(inventory, controls);
        rejectUnsafeExternalReal(inventory, controls, mandatoryExternalSites);

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
                boolean recordedExact = control.resolverOrder().contains(
                        MirrorPlan.MirrorSource.RECORDED_EXACT);
                boolean recordedTrajectory = control.resolverOrder().contains(
                        MirrorPlan.MirrorSource.RECORDED_TRAJECTORY);
                boolean recordedCluster = control.resolverOrder().contains(
                        MirrorPlan.MirrorSource.RECORDED_CLUSTER);
                boolean corpusOnly = control.implicitDeny()
                        && (recordedExact
                        || recordedTrajectory
                        || recordedCluster);
                FixtureRule.BehaviorKind kind = corpusOnly
                        ? FixtureRule.BehaviorKind.REPLAY : first.behavior().kind();
                sites.add(new EffectiveExecutionPlan.ResolvedSite(control.site().invocationSiteId(),
                        corpusOnly ? EffectiveExecutionPlan.Resolution.TEST_DOUBLE
                                : resolution(control),
                        kind, first.behavior().boundary(),
                        corpusOnly ? List.of()
                                : control.rules().stream().map(FixtureRule::ruleId).toList(),
                        corpusOnly
                                ? recordedFidelity(
                                recordedExact,
                                recordedTrajectory,
                                recordedCluster)
                                : fidelity(control)));
            }
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("externalEffects", "DENY");
        defaults.put("selectorZeroMatch", "FAIL");
        defaults.put("selectorAmbiguity", "FAIL");
        defaults.put("productionControl", "REJECT");
        if (mirrorCompilation) {
            defaults.put("mirrorResolverPrecedence", "FIXED_V1");
        }
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
        resolvedCorpus.servingGenerationToken().ifPresent(token ->
                fingerprintMaterial.put("mirrorServingGeneration", Map.of(
                        "streamId", token.material().streamId(),
                        "generation", token.material().generation(),
                        "tokenFingerprint", token.tokenFingerprint(),
                        "dependencyClosureFingerprint",
                        token.material().dependencyClosureFingerprint(),
                        "revocationCursor",
                        token.material().revocationCursor(),
                        "expiresAt", token.material().expiresAt(),
                        "maximumStaleness",
                        token.material().maximumStaleness())));
        fingerprintMaterial.put("executionServiceBindings", executionServices.bindings());
        if (mirrorCompilation) {
            fingerprintMaterial.put("mandatoryMirrorExternalSites", mandatoryExternalSites);
            fingerprintMaterial.put("mirrorResolverOrderBySite", controls.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            entry -> entry.getValue().resolverOrder(),
                            (left, right) -> left, java.util.TreeMap::new)));
        }
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
                        inventory, planFingerprint, providerState, resolvedSecrets);
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
                Map.copyOf(defaults),
                List.of());
        return new CompiledExecutionControl(effectivePlan, controls, fixtureBundle.rules(), inventory,
                resolvedReplays, resolvedCorpus, executionServices);
    }

    /**
     * Rejects governed trajectories that the frozen BLOGE node can never finish.
     *
     * <p>A trajectory contains the initial delegate call plus every governed retry. BLOGE models
     * only retries in {@code retryAttempts}, so the node's total capacity is
     * {@code retryAttempts + 1}. Checking this during compilation prevents a valid publication
     * from degrading into an attempt-exhausted runtime failure merely because the selected graph
     * has a weaker resilience policy.</p>
     */
    private static void rejectTrajectoryRetryIncompatibility(
            InvocationInventory inventory,
            ResolvedCorpusPayloads resolvedCorpus,
            Set<String> recordedTrajectorySites) {
        List<String> incompatible = new ArrayList<>();
        for (String siteId : recordedTrajectorySites) {
            InvocationInventory.Entry entry =
                    inventory.byInvocationSiteId().get(siteId);
            ResolvedCorpusPayloads.CapabilityCorpus corpus =
                    resolvedCorpus.forSite(siteId)
                            .orElseThrow(() -> new ControlPlanRejectedException(
                                    "CONTROL_PLAN_CORPUS_SITE_UNRESOLVED",
                                    List.of("Recorded trajectory corpus site has no bound corpus.")));
            int requiredAttempts = corpus.trajectories().stream()
                    .mapToInt(trajectory -> trajectory.attempts().size())
                    .max()
                    .orElse(0);
            int availableAttempts = entry.node().resilience().retryAttempts() + 1;
            if (requiredAttempts > availableAttempts) {
                incompatible.add(siteId + " requires " + requiredAttempts
                        + " attempts but node capacity is " + availableAttempts);
            }
        }
        if (!incompatible.isEmpty()) {
            throw new ControlPlanRejectedException(
                    "CONTROL_PLAN_TRAJECTORY_RETRY_INCOMPATIBLE", incompatible);
        }
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
            Map<String, CompiledExecutionControl.ResolvedControl> controls,
            Set<String> mandatoryExternalSites) {
        controls.forEach((siteId, control) -> {
            InvocationInventory.Entry entry = inventory.byInvocationSiteId().get(siteId);
            if (entry == null || control.implicitDeny()
                    || (!externalEffect(entry) && !mandatoryExternalSites.contains(siteId))) {
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

    private static Set<String> normalizedSites(Set<String> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String siteId : siteIds) {
            String value = siteId == null ? "" : siteId.trim();
            if (value.isBlank()) {
                throw new ControlPlanRejectedException(
                        "CONTROL_PLAN_MIRROR_SITE_INVALID", List.of(
                        "Capability-derived mirror invocation site id must not be blank."));
            }
            normalized.add(value);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static void rejectMirrorInternalControls(
            Map<String, CompiledExecutionControl.ResolvedControl> controls,
            Set<String> mandatoryExternalSites,
            boolean mirrorCompilation) {
        if (!mirrorCompilation) {
            return;
        }
        List<String> internalSites = controls.keySet().stream()
                .filter(siteId -> !mandatoryExternalSites.contains(siteId)).sorted().toList();
        if (!internalSites.isEmpty()) {
            throw new ControlPlanRejectedException("CONTROL_PLAN_MIRROR_INTERNAL_CONTROL", List.of(
                    "Mirror fixtures may control external capability leaves only: "
                            + internalSites));
        }
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

    private static String recordedFidelity(
            boolean exact,
            boolean trajectory,
            boolean cluster) {
        List<String> sources = new ArrayList<>(3);
        if (exact) {
            sources.add("EXACT");
        }
        if (trajectory) {
            sources.add("TRAJECTORY");
        }
        if (cluster) {
            sources.add("CLUSTER");
        }
        return "RECORDED_" + String.join("+", sources);
    }

    private static final class SetLike {
        private static final List<FixtureRule.BehaviorKind> REAL = List.of(
                FixtureRule.BehaviorKind.REAL, FixtureRule.BehaviorKind.SPY);

        private SetLike() {
        }
    }
}
