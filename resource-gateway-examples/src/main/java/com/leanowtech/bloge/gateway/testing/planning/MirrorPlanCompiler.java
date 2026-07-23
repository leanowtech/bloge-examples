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
import com.leanowtech.bloge.core.spi.ExecutionServiceKind;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles a sealed capability closure and an existing FixtureBundle into one exact MirrorPlan.
 *
 * <p>The compiler is a pure control-plane boundary. It independently verifies the closure, binds
 * every external capability dependency edge to the recursively frozen BLOGE invocation inventory,
 * delegates selector and behavior compilation to {@link ExecutionControlCompiler}, and then seals
 * the payload-free public plan. Mutable registries are not consulted after constructor entry and no
 * graph node is scheduled during compilation.</p>
 *
 * <p>Stage 1 intentionally rejects scenario packs, state models, and schema synthesis. Those
 * protocol fields are reserved for later runtimes; accepting them before a serving implementation
 * exists would create false capability.</p>
 */
public class MirrorPlanCompiler {
    private static final String ROOT_PATH = "/root";

    private final ObjectMapper mapper;
    private final InvocationInventoryBuilder inventoryBuilder;
    private final ExecutionControlCompiler executionControlCompiler;

    /**
     * Creates a compiler over the same frozen root registry used by the independent test runtime.
     *
     * @param registry root BLOGE operator registry
     * @param mapper canonical protocol mapper
     */
    public MirrorPlanCompiler(OperatorRegistry registry, ObjectMapper mapper) {
        Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.inventoryBuilder = new InvocationInventoryBuilder(registry);
        this.executionControlCompiler = new ExecutionControlCompiler(registry, mapper);
    }

    /**
     * Compiles and seals one stateless mirror execution generation.
     *
     * @param request exact already-authorized artifacts and policy
     * @return sealed public plan plus its frozen in-process execution control
     * @throws MirrorPlanRejectedException when any closure, runtime, fixture, or policy fact drifts
     */
    public CompiledMirrorPlan compile(MirrorPlanCompilationRequest request) {
        Objects.requireNonNull(request, "request");
        CapabilityClosure closure = verifiedClosure(request.capabilityClosure());
        Map<MirrorArtifactRef, CapabilitySnapshot> snapshots = snapshots(closure);
        CapabilitySnapshot root = snapshots.get(closure.rootRef());
        validateStageOneInputs(request, root, snapshots.values());

        InvocationInventory inventory;
        try {
            inventory = inventoryBuilder.build(request.graph(), request.graphArtifactFingerprint());
        } catch (ControlPlanRejectedException failure) {
            throw controlFailure(failure);
        }
        if (inventory.entries().size() > request.policy().maximumInvocations()) {
            throw reject("RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL",
                    "Static invocation inventory already exceeds the whole-run occurrence budget.");
        }
        List<ResolvedExternalEdge> edges = resolveExternalEdges(
                closure.rootRef(), root, ROOT_PATH, snapshots, inventory);
        Set<String> mandatorySites = edges.stream()
                .map(edge -> edge.entry().site().invocationSiteId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        rejectUnclosedRuntimeExternals(inventory, mandatorySites);
        ResolvedCorpusPayloads corpusPayloads = bindCorpusPayloads(
                request.corpusPayloads(), edges);

        CompiledExecutionControl control;
        try {
            control = executionControlCompiler.compileMirrorFromInventory(
                    request.graph(), request.fixtureBundle(),
                    request.policy().authorizedPurpose(), request.graphArtifactFingerprint(),
                    request.replayPayloads(), mandatorySites, inventory, corpusPayloads);
        } catch (ControlPlanRejectedException failure) {
            throw controlFailure(failure);
        }
        validateCertificationPolicy(request.policy(), edges, control);

        FixtureBundle fixture = request.fixtureBundle();
        FixtureExecutionServices fixtureServices;
        try {
            fixtureServices = FixtureExecutionServices.from(fixture);
        } catch (IllegalArgumentException invalid) {
            throw reject("RG.MIRROR.EXECUTION_SERVICES_INVALID",
                    "Fixture execution services are invalid.");
        }
        MirrorPlan.ExecutionServices services = new MirrorPlan.ExecutionServices(
                fixture.logicalClock(), fixture.randomSeed(),
                fixtureServiceRef(fixture, fixtureServices, ExecutionServiceKind.IDENTITY),
                fixtureServiceRef(fixture, fixtureServices, ExecutionServiceKind.FEATURE_FLAG));
        MirrorArtifactRef fixtureRef = new MirrorArtifactRef("FIXTURE_BUNDLE",
                fixture.fixtureBundleId(), fixture.revision(),
                control.effectivePlan().fixtureBundleFingerprint());
        List<MirrorArtifactRef> stateModels = stateModelRefs(snapshots.values());
        List<MirrorPlan.ExternalBinding> bindings = edges.stream()
                .map(edge -> externalBinding(edge, control)).toList();
        MirrorPlan plan = new MirrorPlan("", request.planId(), "", closure.rootRef(),
                closure.fingerprint(), closure.snapshots(), root.scope(), fixtureRef,
                control.effectivePlan().planFingerprint(),
                corpusPayloads.servingGenerationToken().orElse(null),
                bindings, request.scenarioPackRef(),
                stateModels, services, request.policy(), request.compiledAt(), request.expiresAt());
        try {
            return new CompiledMirrorPlan(MirrorPlanIntegrity.seal(mapper, plan),
                    request.graph(), request.fixtureBundle(), control);
        } catch (IllegalArgumentException invalid) {
            throw reject("RG.MIRROR.PLAN_INTEGRITY_REJECTED", invalid.getMessage());
        }
    }

    private CapabilityClosure verifiedClosure(CapabilityClosure closure) {
        try {
            CapabilityClosureIntegrity.verify(mapper, closure);
            return closure;
        } catch (IllegalArgumentException invalid) {
            throw reject("RG.MIRROR.CAPABILITY_CLOSURE_INVALID",
                    "Capability closure failed exact integrity verification.");
        }
    }

    private void validateStageOneInputs(
            MirrorPlanCompilationRequest request,
            CapabilitySnapshot root,
            Iterable<CapabilitySnapshot> snapshots) {
        if (!request.graphArtifactFingerprint().equals(root.source().sourceFingerprint())) {
            throw reject("RG.MIRROR.GRAPH_ARTIFACT_DRIFT",
                    "Graph artifact fingerprint does not match the root capability source.");
        }
        if (!matchesGraph(root, request.graph())) {
            throw reject("RG.MIRROR.ROOT_GRAPH_IDENTITY_MISMATCH",
                    "Root capability identity does not match the selected BLOGE graph.");
        }
        if (request.fixtureBundle().logicalClock() == null
                || request.fixtureBundle().randomSeed() == null) {
            throw reject("RG.MIRROR.DETERMINISTIC_SERVICES_REQUIRED",
                    "Mirror compilation requires FixtureBundle logicalClock and randomSeed.");
        }
        if (request.scenarioPackRef() != null) {
            throw reject("RG.MIRROR.SCENARIO_PACK_NOT_AVAILABLE",
                    "Scenario packs require the Stage 4 rehearsal runtime.");
        }
        if (request.policy().schemaSynthesisAllowed()) {
            throw reject("RG.MIRROR.SCHEMA_SYNTHESIS_NOT_AVAILABLE",
                    "Schema synthesis requires the governed corpus runtime.");
        }
        CapabilityContract.DataClassification fixtureClassification;
        try {
            fixtureClassification = CapabilityContract.DataClassification.valueOf(
                    request.fixtureBundle().classification().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw reject("RG.MIRROR.FIXTURE_CLASSIFICATION_INVALID",
                    "Fixture classification is not supported by mirror policy.");
        }
        if (fixtureClassification.ordinal()
                > request.policy().maximumClassification().ordinal()) {
            throw reject("RG.MIRROR.FIXTURE_CLASSIFICATION_FORBIDDEN",
                    "Fixture classification exceeds mirror-plan clearance.");
        }
        if (!stateModelRefs(snapshots).isEmpty()) {
            throw reject("RG.MIRROR.STATEFUL_RUNTIME_NOT_AVAILABLE",
                    "State-model dependencies require the Stage 3 session runtime.");
        }
    }

    private List<ResolvedExternalEdge> resolveExternalEdges(
            MirrorArtifactRef rootRef,
            CapabilitySnapshot root,
            String rootPath,
            Map<MirrorArtifactRef, CapabilitySnapshot> snapshots,
            InvocationInventory inventory) {
        List<ResolvedExternalEdge> resolved = new ArrayList<>();
        Set<ComposedRuntimeCoordinate> visited = new HashSet<>();
        resolveComposed(rootRef, root, rootPath, snapshots, inventory, visited, resolved);
        return resolved.stream().sorted(Comparator.comparing(
                        edge -> edge.entry().site().invocationSiteId()))
                .toList();
    }

    private void resolveComposed(
            MirrorArtifactRef parentRef,
            CapabilitySnapshot parent,
            String graphPath,
            Map<MirrorArtifactRef, CapabilitySnapshot> snapshots,
            InvocationInventory inventory,
            Set<ComposedRuntimeCoordinate> visited,
            List<ResolvedExternalEdge> resolved) {
        if (!visited.add(new ComposedRuntimeCoordinate(parentRef, graphPath))) {
            throw reject("RG.MIRROR.RUNTIME_GRAPH_REENTRY",
                    "One composed capability was bound repeatedly to the same runtime graph path.");
        }
        for (CapabilitySnapshot.Dependency dependency : parent.dependencies()) {
            CapabilitySnapshot child = snapshots.get(dependency.capabilityRef());
            if (child == null) {
                throw reject("RG.MIRROR.CAPABILITY_DEPENDENCY_MISSING",
                        "Capability dependency is absent from the verified closure.");
            }
            if (child.kind() == CapabilitySnapshot.Kind.EXTERNAL) {
                InvocationInventory.Entry entry = exactExternalEntry(
                        graphPath, dependency, child, inventory);
                resolved.add(new ResolvedExternalEdge(parentRef, dependency, child, entry));
            } else {
                String childPath = exactNestedGraphPath(graphPath, dependency, child, inventory);
                resolveComposed(dependency.capabilityRef(), child, childPath,
                        snapshots, inventory, visited, resolved);
            }
        }
    }

    private InvocationInventory.Entry exactExternalEntry(
            String graphPath,
            CapabilitySnapshot.Dependency dependency,
            CapabilitySnapshot child,
            InvocationInventory inventory) {
        List<InvocationInventory.Entry> matches = inventory.entries().stream()
                .filter(entry -> entry.site().graphPath().equals(graphPath))
                .filter(entry -> entry.node().id().equals(dependency.nodeId()))
                .filter(entry -> entry.site().invocationKind()
                        != com.leanowtech.bloge.gateway.testing.domain.InvocationSite
                        .InvocationKind.COMPENSATION)
                .toList();
        if (matches.size() != 1) {
            throw reject(matches.isEmpty()
                            ? "RG.MIRROR.EXTERNAL_RUNTIME_SITE_MISSING"
                            : "RG.MIRROR.EXTERNAL_RUNTIME_SITE_AMBIGUOUS",
                    "External capability dependency must resolve to exactly one runtime invocation site.");
        }
        InvocationInventory.Entry entry = matches.getFirst();
        if (child.source().sourceKind() == CapabilitySnapshot.SourceKind.RESOURCE) {
            if (!"httpResource".equals(entry.node().operatorRef())) {
                throw reject("RG.MIRROR.RESOURCE_RUNTIME_BINDING_MISMATCH",
                        "Resource capability must bind the BLOGE httpResource operator.");
            }
        } else if (child.source().sourceKind() == CapabilitySnapshot.SourceKind.OPERATOR) {
            if (!child.source().sourceRef().equals(entry.node().operatorRef())) {
                throw reject("RG.MIRROR.OPERATOR_RUNTIME_BINDING_MISMATCH",
                        "Operator capability source does not match the BLOGE node binding.");
            }
            String operatorFingerprint = entry.node().operatorFingerprint();
            if (operatorFingerprint != null && !operatorFingerprint.isBlank()
                    && !operatorFingerprint.equals(child.source().sourceFingerprint())) {
                throw reject("RG.MIRROR.OPERATOR_RUNTIME_FINGERPRINT_DRIFT",
                        "Operator capability fingerprint does not match the BLOGE node binding.");
            }
        } else {
            throw reject("RG.MIRROR.EXTERNAL_SOURCE_KIND_INVALID",
                    "External capability cannot use a graph source kind.");
        }
        return entry;
    }

    private String exactNestedGraphPath(
            String parentPath,
            CapabilitySnapshot.Dependency dependency,
            CapabilitySnapshot child,
            InvocationInventory inventory) {
        String prefix = parentPath + "/" + escape(dependency.nodeId()) + "/";
        Map<String, Graph> candidateGraphs = new LinkedHashMap<>();
        for (InvocationInventory.Entry entry : inventory.entries()) {
            String path = entry.site().graphPath();
            if (!path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) {
                continue;
            }
            candidateGraphs.putIfAbsent(path, entry.graph());
        }
        List<String> matches = candidateGraphs.entrySet().stream()
                .filter(entry -> matchesGraph(child, entry.getValue()))
                .map(Map.Entry::getKey).toList();
        if (matches.size() != 1) {
            throw reject(matches.isEmpty()
                            ? "RG.MIRROR.NESTED_RUNTIME_SITE_MISSING"
                            : "RG.MIRROR.NESTED_RUNTIME_SITE_AMBIGUOUS",
                    "Nested capability must resolve to exactly one owned BLOGE graph path.");
        }
        return matches.getFirst();
    }

    private void rejectUnclosedRuntimeExternals(
            InvocationInventory inventory,
            Set<String> boundSites) {
        List<String> unclosed = inventory.entries().stream()
                .filter(MirrorPlanCompiler::runtimeExternal)
                .map(entry -> entry.site().invocationSiteId())
                .filter(site -> !boundSites.contains(site)).sorted().toList();
        if (!unclosed.isEmpty()) {
            throw new MirrorPlanRejectedException("RG.MIRROR.RUNTIME_EXTERNAL_NOT_IN_CLOSURE",
                    List.of("Runtime external sites are absent from the capability closure: " + unclosed));
        }
    }

    private static boolean runtimeExternal(InvocationInventory.Entry entry) {
        if ("httpResource".equals(entry.node().operatorRef())) {
            return true;
        }
        Object operator = entry.frozenOperator();
        if (operator instanceof SubGraphOperator
                || operator instanceof ForEachOperator
                || operator instanceof LoopOperator
                || operator instanceof ParallelSubGraphOperator
                || operator instanceof StreamingForEachOperator
                || operator instanceof StreamingLoopOperator) {
            return false;
        }
        return operator instanceof Operator<?, ?> typed
                && typed.sideEffectType() != SideEffectType.READ_ONLY;
    }

    private static void validateCertificationPolicy(
            MirrorPlan.ExecutionPolicy policy,
            List<ResolvedExternalEdge> edges,
            CompiledExecutionControl control) {
        if (!policy.certificationRequired()) {
            return;
        }
        for (ResolvedExternalEdge edge : edges) {
            CompiledExecutionControl.ResolvedControl resolved = control.controls().get(
                    edge.entry().site().invocationSiteId());
            if (resolved != null && resolved.rules().stream().anyMatch(rule ->
                    rule.schemaCheck().mode() == FixtureRule.SchemaCheckMode.WAIVED)) {
                throw reject("RG.MIRROR.CERTIFICATION_SCHEMA_WAIVER_FORBIDDEN",
                        "Certification-required mirror plans cannot use schema-waived fixtures.");
            }
        }
    }

    private MirrorPlan.ExternalBinding externalBinding(
            ResolvedExternalEdge edge,
            CompiledExecutionControl control) {
        String siteId = edge.entry().site().invocationSiteId();
        CompiledExecutionControl.ResolvedControl resolved = control.controls().get(siteId);
        if (resolved == null) {
            throw reject("RG.MIRROR.EXTERNAL_CONTROL_MISSING",
                    "External runtime site has no frozen execution control.");
        }
        List<FixtureRule> rules = resolved.implicitDeny() ? List.of() : resolved.rules();
        return new MirrorPlan.ExternalBinding(edge.parentRef(), edge.dependency().nodeId(),
                edge.dependency().capabilityRef(), siteId, edge.entry().site().graphPath(),
                edge.child().source().sourceKind(), edge.child().source().sourceRef(),
                resolved.resolverOrder(), rules.stream().map(FixtureRule::ruleId).toList());
    }

    private ResolvedCorpusPayloads bindCorpusPayloads(
            ResolvedCorpusPayloads corpusPayloads,
            List<ResolvedExternalEdge> edges) {
        ResolvedCorpusPayloads exact = corpusPayloads == null
                ? ResolvedCorpusPayloads.empty() : corpusPayloads;
        if (!exact.isEmpty()
                && exact.servingGenerationToken().isEmpty()) {
            throw reject(
                    "RG.MIRROR.SERVING_GENERATION_REQUIRED",
                    "Recorded corpus payloads require a signed current serving generation.");
        }
        exact.servingGenerationToken().ifPresent(token -> {
            String dependencies = ProtocolFingerprint.of(
                    mapper, exact.generationDependencies());
            if (!dependencies.equals(
                    token.material().dependencyClosureFingerprint())) {
                throw reject(
                        "RG.MIRROR.SERVING_GENERATION_DEPENDENCY_MISMATCH",
                        "Serving-generation token does not bind the materialized corpus dependencies.");
            }
        });
        Set<MirrorArtifactRef> externalCapabilities = edges.stream()
                .map(edge -> edge.dependency().capabilityRef())
                .collect(java.util.stream.Collectors.toSet());
        List<MirrorArtifactRef> unused = exact.capabilityRefs().stream()
                .filter(ref -> !externalCapabilities.contains(ref))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (!unused.isEmpty()) {
            throw reject("RG.MIRROR.CORPUS_CAPABILITY_NOT_IN_CLOSURE",
                    "Fixture corpus publications must bind external capabilities in the closure.");
        }
        Map<String, MirrorArtifactRef> capabilityBySite = new LinkedHashMap<>();
        edges.forEach(edge -> capabilityBySite.put(
                edge.entry().site().invocationSiteId(),
                edge.dependency().capabilityRef()));
        return exact.bindSites(capabilityBySite);
    }

    private MirrorArtifactRef fixtureServiceRef(
            FixtureBundle fixture,
            FixtureExecutionServices services,
            ExecutionServiceKind kind) {
        if (!services.configures(kind)) {
            return null;
        }
        String artifactKind = kind == ExecutionServiceKind.IDENTITY
                ? "IDENTITY_FIXTURE" : "FEATURE_FLAG_FIXTURE";
        String suffix = kind == ExecutionServiceKind.IDENTITY ? "identity" : "feature-flags";
        return new MirrorArtifactRef(artifactKind,
                fixture.fixtureBundleId() + ":" + suffix, fixture.revision(),
                ProtocolFingerprint.of(mapper, services.configuration(kind)));
    }

    private static List<MirrorArtifactRef> stateModelRefs(
            Iterable<CapabilitySnapshot> snapshots) {
        Set<MirrorArtifactRef> refs = new LinkedHashSet<>();
        for (CapabilitySnapshot snapshot : snapshots) {
            if (snapshot.contract().stateModelRef() != null) {
                refs.add(snapshot.contract().stateModelRef());
            }
        }
        return refs.stream().sorted(Comparator.comparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision)
                .thenComparing(MirrorArtifactRef::fingerprint)).toList();
    }

    private static Map<MirrorArtifactRef, CapabilitySnapshot> snapshots(CapabilityClosure closure) {
        Map<MirrorArtifactRef, CapabilitySnapshot> snapshots = new LinkedHashMap<>();
        closure.snapshots().forEach(snapshot -> snapshots.put(
                CapabilityClosureIntegrity.reference(snapshot), snapshot));
        return Map.copyOf(snapshots);
    }

    private static boolean matchesGraph(CapabilitySnapshot capability, Graph graph) {
        return capability.capabilityId().equals("graph:" + graph.name())
                || capability.source().sourceRef().equals(graph.name());
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static MirrorPlanRejectedException controlFailure(
            ControlPlanRejectedException failure) {
        return new MirrorPlanRejectedException("RG.MIRROR." + failure.code(),
                failure.diagnostics());
    }

    private static MirrorPlanRejectedException reject(String code, String diagnostic) {
        return new MirrorPlanRejectedException(code, List.of(diagnostic));
    }

    private record ResolvedExternalEdge(
            MirrorArtifactRef parentRef,
            CapabilitySnapshot.Dependency dependency,
            CapabilitySnapshot child,
            InvocationInventory.Entry entry
    ) {
    }

    private record ComposedRuntimeCoordinate(MirrorArtifactRef capabilityRef, String graphPath) {
    }
}
