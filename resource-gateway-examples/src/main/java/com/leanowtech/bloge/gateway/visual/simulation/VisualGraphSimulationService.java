package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationExecutor;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationPlan;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a visual graph draft as a <b>mock run</b> (simulate) for runtime-correctness validation.
 *
 * <p>This is the flagship capability of the generic canvas (decision D4/D5/D6): a graph can reference
 * operators that the server has not implemented yet (schema-only, design-only operators). The normal
 * run path blocks such graphs at the action-readiness gate. Simulate instead makes the graph
 * executable by substituting schema-conforming stand-ins, so the author can validate that the graph
 * wires up and runs end-to-end.</p>
 *
 * <h2>Hybrid strategy (D5, D19)</h2>
 * <p>Nodes that lower to BLOGE <b>DSL primitives</b> (transform / decision-table / branch) execute for
 * real, because those are pure, deterministic, side-effect-free constructs evaluated by the engine
 * without an operator lookup. Every other node — design-only operators, HTTP/resource operators, native
 * Java operators, publication subgraphs — is <b>mocked</b>: it is rewritten to a per-node native
 * stand-in {@code __sim_<nodeId>} whose output is synthesized from the operator's declared output
 * schema. Mocking all operator-invoking nodes keeps the simulation free of side effects and inherently
 * defends against ReDoS/recursion (template/regex/subgraph operators are never really executed), which
 * is a conservative, safe realization of the D19 allowlist.</p>
 *
 * <h2>Safety (D21)</h2>
 * <p>Simulation is bounded by a generated-run timeout plus node and edge caps; sample synthesis is
 * itself depth/size bounded by {@link JsonSchemaSampleGenerator}; and because HTTP/resource operators
 * are mocked, simulation performs no outbound calls (no SSRF surface).</p>
 */
@Service
public class VisualGraphSimulationService {

    /** Prefix for the per-node native simulation stand-in operator reference. */
    static final String SIM_OPERATOR_PREFIX = "__sim_";

    /** Maximum number of nodes a single simulation may contain (D21 resource guard). */
    static final int MAX_SIMULATION_NODES = 200;

    /** Maximum number of edges a single simulation may contain (D21 resource guard). */
    static final int MAX_SIMULATION_EDGES = 400;

    /** Maximum time spent inside the generated DSL runner for one simulation (D21 timeout guard). */
    static final Duration DEFAULT_SIMULATION_RUN_TIMEOUT = Duration.ofSeconds(10);

    /** Lowering modes that emit a real, pure BLOGE DSL primitive rather than an operator invocation. */
    private static final Set<String> PRIMITIVE_LOWERING_MODES = Set.of("transform", "branch");

    /** Operator references that emit a real, pure BLOGE DSL primitive rather than an operator invocation. */
    private static final Set<String> PRIMITIVE_OPERATOR_REFS = Set.of("bloge:transform", "bloge:decisionTable");

    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;
    private final JsonSchemaSampleGenerator sampleGenerator;
    private final VisualDslRunnerFactory runnerFactory;
    private final VisualSimulationExecutor simulationExecutor;
    private final Duration runTimeout;
    private final boolean serverProductionDeployment;
    private final String configuredDeploymentEnvironment;

    /**
     * @param validator visual draft validator (reused; the action-readiness run gate is intentionally
     *                  not consulted so design-only graphs can be simulated)
     * @param catalog visual operator catalog used for classification and schema lookup
     * @param sampleGenerator deterministic JSON Schema sample generator
     * @param runnerFactory creates DSL runners for simulation-specific operator registries
     */
    public VisualGraphSimulationService(GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        JsonSchemaSampleGenerator sampleGenerator,
                                        VisualDslRunnerFactory runnerFactory) {
        this(validator, catalog, sampleGenerator, runnerFactory,
                null, DEFAULT_SIMULATION_RUN_TIMEOUT, VisualProductionAdmissionPolicy.nonProductionTest());
    }

    /**
     * Spring-owned construction uses immutable deployment evidence rather than request data.
     */
    @Autowired
    public VisualGraphSimulationService(GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        JsonSchemaSampleGenerator sampleGenerator,
                                        VisualDslRunnerFactory runnerFactory,
                                        VisualSimulationExecutor simulationExecutor,
                                        VisualProductionAdmissionPolicy deploymentPolicy) {
        this(validator, catalog, sampleGenerator, runnerFactory,
                simulationExecutor, DEFAULT_SIMULATION_RUN_TIMEOUT,
                deploymentPolicy);
    }

    /**
     * Creates a simulation service with a custom runner timeout.
     *
     * <p>This constructor is package-private so tests can assert timeout behavior without slowing down
     * the production default. The timeout guards the generated DSL execution stage, after validation
     * and sample synthesis have already completed.</p>
     */
    VisualGraphSimulationService(GraphDraftValidator validator,
                                 VisualOperatorCatalog catalog,
                                 JsonSchemaSampleGenerator sampleGenerator,
                                 VisualDslRunnerFactory runnerFactory,
                                 Duration runTimeout) {
        this(validator, catalog, sampleGenerator, runnerFactory,
                null, runTimeout, VisualProductionAdmissionPolicy.nonProductionTest());
    }

    /** Test seam for immutable server deployment evidence. */
    VisualGraphSimulationService(GraphDraftValidator validator,
                                 VisualOperatorCatalog catalog,
                                 JsonSchemaSampleGenerator sampleGenerator,
                                 VisualDslRunnerFactory runnerFactory,
                                 Duration runTimeout,
                                 boolean productionProfileActive,
                                 String configuredDeploymentEnvironment) {
        this(validator, catalog, sampleGenerator, runnerFactory,
                null, runTimeout, VisualProductionAdmissionPolicy.fromEvidence(
                        productionProfileActive, configuredDeploymentEnvironment));
    }

    /** Test seam for the kernel-backed execution path. */
    VisualGraphSimulationService(GraphDraftValidator validator,
                                 VisualOperatorCatalog catalog,
                                 JsonSchemaSampleGenerator sampleGenerator,
                                 VisualDslRunnerFactory runnerFactory,
                                 VisualSimulationExecutor simulationExecutor,
                                 Duration runTimeout,
                                 VisualProductionAdmissionPolicy deploymentPolicy) {
        this.validator = validator;
        this.catalog = catalog;
        this.sampleGenerator = sampleGenerator;
        this.runnerFactory = runnerFactory;
        this.simulationExecutor = simulationExecutor;
        this.runTimeout = normalizeTimeout(runTimeout);
        VisualProductionAdmissionPolicy policy = Objects.requireNonNull(
                deploymentPolicy, "deploymentPolicy");
        this.configuredDeploymentEnvironment = policy.environmentId();
        this.serverProductionDeployment = policy.productionDeployment();
    }

    /** Test seam for the shared immutable server deployment policy. */
    VisualGraphSimulationService(GraphDraftValidator validator,
                                 VisualOperatorCatalog catalog,
                                 JsonSchemaSampleGenerator sampleGenerator,
                                 VisualDslRunnerFactory runnerFactory,
                                 Duration runTimeout,
                                 VisualProductionAdmissionPolicy deploymentPolicy) {
        this(validator, catalog, sampleGenerator, runnerFactory,
                null, runTimeout, deploymentPolicy);
    }

    /**
     * Simulates a visual graph draft with no transient fixture overrides.
     *
     * <p>Persisted {@link GraphDraft#nodeFixtures()} still apply; use the overload with
     * {@code fixtures} when a caller wants request-scoped overrides.</p>
     *
     * @param draft the graph draft
     * @param context the initial graph context (may be partial; missing inputs simply bind to null)
     * @param outputNode optional output node override; defaults to the draft's selected output node
     * @return the simulation result, including which nodes were mocked vs executed for real
     */
    public VisualGraphSimulationResponse simulate(GraphDraft draft,
                                                  Map<String, Object> context,
                                                  String outputNode) {
        return simulate(draft, context, outputNode, Map.of());
    }

    /**
     * Simulates a visual graph draft, honouring persisted and request-scoped per-node fixtures.
     *
     * <p>A node with a fixture is always mocked and its output is the fixture value, taking precedence
     * over both the schema-synthesized sample and the hybrid real-run classification (decisions D4,
     * D20): pinning forces the node to be mocked even if it would otherwise execute for real.</p>
     *
     * @param draft the graph draft
     * @param context the initial graph context (may be partial; missing inputs simply bind to null)
     * @param outputNode optional output node override; defaults to the draft's selected output node
     * @param fixtures request-scoped per-node fixtures keyed by node id; these override any persisted draft fixture
     * @return the simulation result, including which nodes were mocked vs executed for real
     */
    public VisualGraphSimulationResponse simulate(GraphDraft draft,
                                                  Map<String, Object> context,
                                                  String outputNode,
                                                  Map<String, NodeFixture> fixtures) {
        rejectProductionSimulation();
        if (draft == null) {
            return blocked(false, List.of(VisualDiagnostic.error("visual.simulate.draftMissing",
                    "Graph draft is required.", "/")), List.of("Graph draft is required."), "");
        }
        Map<String, NodeFixture> effectiveFixtures = effectiveFixtures(draft, fixtures);
        List<VisualDiagnostic> capDiagnostics = enforceResourceCaps(draft);
        if (!capDiagnostics.isEmpty()) {
            return blocked(false, capDiagnostics, List.of("Simulation resource caps exceeded."), "");
        }

        VisualValidationResult validation = validator.validate(draft);
        if (!validation.valid()) {
            return blocked(false, validation.diagnostics(), List.of("Visual validation failed."), "");
        }

        // Classify each node: mock (operator-invoking) or real (DSL primitive), and build the
        // simulation draft, the synthetic catalog entries, and the per-node mock outputs.
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, OperatorDefinition> syntheticDefinitions = new LinkedHashMap<>();
        Map<String, Object> mockOutputsByNodeId = new LinkedHashMap<>();
        List<String> mockedNodeIds = new ArrayList<>();
        List<String> realNodeIds = new ArrayList<>();
        List<GraphDraft.DraftNode> simulationNodes = new ArrayList<>();

        for (GraphDraft.DraftNode node : draft.nodes()) {
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.simulate.operatorUnknown",
                        "Operator '%s' on node '%s' is unknown and cannot be simulated."
                                .formatted(node.operatorRef(), node.id()),
                        "/nodes/" + node.id() + "/operatorRef"));
                return blocked(true, diagnostics,
                        List.of("Simulation cannot resolve one or more operators."), "");
            }
            boolean pinned = effectiveFixtures.containsKey(node.id());
            if (pinned && effectiveFixtures.get(node.id()).resourceFidelity()
                    != NodeFixture.ResourceFidelity.OUTPUT_LEVEL
                    && !"httpResource".equals(operator.get().lowering().operatorRef())) {
                diagnostics.add(VisualDiagnostic.error("visual.simulate.resourceFidelity.nonResource",
                        "Protocol or transport fidelity is only valid for resource operators.",
                        "/fixtures/" + node.id() + "/resourceFidelity"));
                return blocked(true, diagnostics,
                        List.of("Resource fidelity is invalid for this operator."), "");
            }
            if (!pinned && isRealPrimitive(operator.get())) {
                realNodeIds.add(node.id());
                simulationNodes.add(node);
            } else {
                String simulationRef = SIM_OPERATOR_PREFIX + node.id();
                mockedNodeIds.add(node.id());
                Object mockOutput = pinned
                        ? effectiveFixtures.get(node.id()).output()
                        : sampleGenerator.generate(firstOutputSchema(operator.get()));
                mockOutputsByNodeId.put(node.id(), mockOutput);
                if (preserveGovernedResourceNode(operator.get(), pinned,
                        effectiveFixtures.get(node.id()))) {
                    /*
                     * Protocol/transport fixtures must retain the descriptor-backed lowering. The
                     * kernel needs the real httpResource invocation site to apply its resource
                     * selector and execute the descriptor boundary; only output-level fixtures use
                     * a synthetic native stand-in.
                     */
                    simulationNodes.add(node);
                } else {
                    syntheticDefinitions.put(simulationRef,
                            syntheticNativeDefinition(simulationRef, operator.get()));
                    simulationNodes.add(rewriteToSimulation(node, simulationRef));
                }
            }
        }

        GraphDraft simulationDraft = withNodes(draft, simulationNodes);
        SimulationOperatorCatalog simulationCatalog = new SimulationOperatorCatalog(catalog, syntheticDefinitions);
        DslGenerationResult generated = new GraphDraftDslGenerator(simulationCatalog).generate(simulationDraft);
        diagnostics.addAll(generated.diagnostics());
        if (!generated.generated()) {
            return blocked(true, diagnostics, List.of("Simulation DSL generation failed."), generated.dsl());
        }

        Map<String, Object> effectiveContext = effectiveContext(draft, context);
        String selectedOutputNode = outputNode == null || outputNode.isBlank()
                ? draft.output().nodeId()
                : outputNode;

        VisualDslRunResponse dynamic;
        Map<String, SimulationOperator> simulationOperators = new LinkedHashMap<>();
        try {
            if (simulationExecutor != null) {
                List<VisualSimulationPlan.Standin> standins = mockedNodeIds.stream()
                        .map(nodeId -> {
                            NodeFixture fixture = effectiveFixtures.get(nodeId);
                            Object output = fixture == null
                                    ? mockOutputsByNodeId.get(nodeId)
                                    : fixture.output();
                            if (fixture != null
                                    && fixture.resourceFidelity() != NodeFixture.ResourceFidelity.OUTPUT_LEVEL
                                    && !hasRawResourceEvidence(output)) {
                                String resourceId = resourceEvidenceId(draft, nodeId);
                                if (resourceId != null) {
                                    output = Map.of("resourceId", resourceId, "governedPayload", output);
                                }
                            }
                            return new VisualSimulationPlan.Standin(
                                    nodeId,
                                    rewrittenSimulationOperatorRef(draft, nodeId, fixture),
                                    output,
                                    fixture == null ? null : fixture.expectedInput(),
                                    fixture == null
                                            ? NodeFixture.ResourceFidelity.OUTPUT_LEVEL
                                            : fixture.resourceFidelity());
                        })
                        .toList();
                dynamic = runKernelWithTimeout(new VisualSimulationPlan(
                        generated.dsl(), effectiveContext, selectedOutputNode, standins));
            } else {
                DefaultOperatorRegistry simulationRegistry = new DefaultOperatorRegistry();
                mockOutputsByNodeId.forEach((nodeId, output) -> {
                    SimulationOperator operator = SimulationOperator.returning(nodeId, output);
                    simulationOperators.put(nodeId, operator);
                    simulationRegistry.register(SIM_OPERATOR_PREFIX + nodeId, operator);
                });
                dynamic = runDslWithTimeout(simulationRegistry,
                        new VisualDslRunRequest(generated.dsl(), effectiveContext, selectedOutputNode));
            }
        } catch (TimeoutException ex) {
            diagnostics.add(VisualDiagnostic.error("visual.simulate.timeout",
                    "Simulation exceeded the %d ms execution timeout.".formatted(runTimeout.toMillis()),
                    "/simulate"));
            return executionBlocked(true, false, diagnostics,
                    List.of("Simulation exceeded the execution timeout."), generated.dsl(),
                    selectedOutputNode, mockedNodeIds, realNodeIds, runTimeout.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            diagnostics.add(VisualDiagnostic.error("visual.simulate.interrupted",
                    "Simulation was interrupted before the generated graph finished.", "/simulate"));
            return executionBlocked(true, false, diagnostics,
                    List.of("Simulation was interrupted."), generated.dsl(),
                    selectedOutputNode, mockedNodeIds, realNodeIds, 0);
        } catch (ExecutionException ex) {
            diagnostics.add(VisualDiagnostic.error("visual.simulate.runnerFailed",
                    "Simulation runner failed: %s.".formatted(rootMessage(ex)), "/simulate"));
            return executionBlocked(true, false, diagnostics,
                    List.of("Simulation runner failed."), generated.dsl(),
                    selectedOutputNode, mockedNodeIds, realNodeIds, 0);
        }
        boolean kernelFixtureMismatch = simulationExecutor != null
                && dynamic.errors().contains("FIXTURE_UNMATCHED");
        if (!kernelFixtureMismatch) {
            for (VisualDslRunResponse.Diagnostic diagnostic : dynamic.diagnostics()) {
                diagnostics.add(fromCompilerDiagnostic(diagnostic));
            }
        }
        List<VisualDiagnostic> assertionDiagnostics = simulationExecutor == null
                ? inputAssertionDiagnostics(effectiveFixtures, simulationOperators)
                : kernelInputMismatchDiagnostics(dynamic, effectiveFixtures);
        diagnostics.addAll(assertionDiagnostics);
        List<String> errors = kernelFixtureMismatch
                ? new ArrayList<>()
                : new ArrayList<>(dynamic.errors());
        assertionDiagnostics.forEach(diagnostic -> errors.add(diagnostic.message()));

        boolean assertionsPass = assertionDiagnostics.isEmpty();
        Object output = dynamic.output();
        if (!assertionsPass && simulationExecutor != null && output == null
                && mockOutputsByNodeId.containsKey(dynamic.outputNode())) {
            output = mockOutputsByNodeId.get(dynamic.outputNode());
        }
        boolean terminalConforms = terminalOutputConforms(draft, dynamic.outputNode(), output);
        Map<String, Object> results = new LinkedHashMap<>(dynamic.results());
        Map<String, String> statusMap = dynamic.statusMap();
        // Graph execution omits completed nodes whose mock output is null.  Authors still need an
        // observable result key so "no output" samples remain pinnable in the browser.
        for (String nodeId : mockOutputsByNodeId.keySet()) {
            results.putIfAbsent(nodeId, mockOutputsByNodeId.get(nodeId));
        }
        if (kernelFixtureMismatch) {
            results = new LinkedHashMap<>(results);
            statusMap = new LinkedHashMap<>(statusMap);
            for (Map.Entry<String, Object> entry : mockOutputsByNodeId.entrySet()) {
                String nodeId = entry.getKey();
                Object mockOutput = entry.getValue();
                results.putIfAbsent(nodeId, mockOutput);
                statusMap.put(nodeId, "COMPLETED");
            }
        }

        return new VisualGraphSimulationResponse(
                true,
                dynamic.compiled(),
                dynamic.success() && assertionsPass,
                dynamic.graphName(),
                dynamic.outputNode(),
                output,
                results,
                statusMap,
                dynamic.elapsedMs(),
                dynamic.nodeElapsedMs(),
                mockedNodeIds,
                realNodeIds,
                terminalConforms,
                diagnostics,
                errors,
                generated.dsl(),
                dynamic.nodeFidelity()
        );
    }

    private void rejectProductionSimulation() {
        if (!serverProductionDeployment) {
            return;
        }
        throw new VisualSimulationProductionAdmissionException();
    }

    private static boolean hasRawResourceEvidence(Object output) {
        if (output instanceof com.leanowtech.bloge.gateway.operator.HttpResourceOutput) {
            return true;
        }
        return output instanceof Map<?, ?> evidence
                && evidence.containsKey("resourceId")
                && evidence.containsKey("rawBody")
                && evidence.containsKey("statusCode");
    }

    /**
     * Keeps a governed resource fixture on its descriptor-backed lowering so the kernel can
     * apply protocol or transport semantics. Output-level fixtures remain synthetic stand-ins
     * and therefore cannot accidentally perform a real resource invocation.
     */
    private boolean preserveGovernedResourceNode(OperatorDefinition operator,
                                                  boolean pinned,
                                                  NodeFixture fixture) {
        return pinned
                && fixture != null
                && fixture.resourceFidelity() != NodeFixture.ResourceFidelity.OUTPUT_LEVEL
                && "httpResource".equals(operator.lowering().operatorRef());
    }

    private String rewrittenSimulationOperatorRef(GraphDraft draft, String nodeId,
                                                  NodeFixture fixture) {
        if (fixture != null && fixture.resourceFidelity() != NodeFixture.ResourceFidelity.OUTPUT_LEVEL) {
            Optional<OperatorDefinition> operator = draft.nodes().stream()
                    .filter(node -> node.id().equals(nodeId))
                    .findFirst()
                    .flatMap(node -> catalog.find(node.operatorRef()));
            if (operator.map(value -> "httpResource".equals(value.lowering().operatorRef()))
                    .orElse(false)) {
                return "httpResource";
            }
        }
        return SIM_OPERATOR_PREFIX + nodeId;
    }

    /**
     * Resolves the protected resource identity from the catalog-owned lowering contract.
     *
     * <p>Visual resource nodes intentionally keep their instance configuration empty; their
     * resource identity lives in the catalog lowering parameters. A legacy/configured value is
     * accepted only as a compatibility fallback. Returning {@code null} remains fail-closed for
     * governed protocol or transport projection.</p>
     *
     * @param draft graph being simulated
     * @param nodeId mocked node whose resource identity is required
     * @return catalog resource identity, compatibility config identity, or {@code null}
     */
    private String resourceEvidenceId(GraphDraft draft, String nodeId) {
        return draft.nodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .map(node -> {
                    Optional<String> catalogResource = catalog.find(node.operatorRef())
                            .map(OperatorDefinition::lowering)
                            .map(OperatorDefinition.Lowering::parameters)
                            .map(parameters -> parameters.get("resourceId"))
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .filter(resourceId -> !resourceId.isBlank());
                    return catalogResource.or(() -> Optional.ofNullable(node.config().get("resourceId"))
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .filter(resourceId -> !resourceId.isBlank()));
                })
                .orElse(Optional.empty())
                .orElse(null);
    }

    private VisualDslRunResponse runDslWithTimeout(DefaultOperatorRegistry simulationRegistry,
                                                   VisualDslRunRequest request)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("visual-simulate-", 0).factory());
        Future<VisualDslRunResponse> future = executor.submit(() ->
                runnerFactory.forRegistry(simulationRegistry).run(request));
        try {
            return future.get(runTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        } finally {
            executor.shutdownNow();
        }
    }

    private VisualDslRunResponse runKernelWithTimeout(VisualSimulationPlan plan)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("visual-simulate-kernel-", 0).factory());
        Future<VisualDslRunResponse> future = executor.submit(() -> simulationExecutor.execute(plan));
        try {
            return future.get(runTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean isRealPrimitive(OperatorDefinition operator) {
        return PRIMITIVE_LOWERING_MODES.contains(operator.lowering().mode())
                || PRIMITIVE_OPERATOR_REFS.contains(operator.operatorRef());
    }

    private static SchemaEnvelope firstOutputSchema(OperatorDefinition operator) {
        List<OperatorDefinition.Port> outputs = operator.ports().outputs();
        return outputs.isEmpty() ? SchemaEnvelope.opaque() : outputs.get(0).schema();
    }

    private static OperatorDefinition syntheticNativeDefinition(String simulationRef,
                                                                OperatorDefinition original) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                simulationRef,
                "1.0.0",
                new OperatorDefinition.Display("Simulated " + original.operatorRef(), "", List.of()),
                OperatorDefinition.Source.builtIn("simulation"),
                original.ports(),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", simulationRef, Map.of()),
                List.of()
        );
    }

    private static GraphDraft.DraftNode rewriteToSimulation(GraphDraft.DraftNode node, String simulationRef) {
        return new GraphDraft.DraftNode(
                node.id(),
                simulationRef,
                node.label(),
                node.inputs(),
                node.config(),
                node.position()
        );
    }

    private static GraphDraft withNodes(GraphDraft draft, List<GraphDraft.DraftNode> nodes) {
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.outputSchema(),
                nodes,
                draft.edges(),
                draft.visualLayout(),
                draft.nodeFixtures(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.operatorSnapshots(),
                draft.revisionMetadata()
        );
    }

    private static Map<String, NodeFixture> effectiveFixtures(GraphDraft draft,
                                                              Map<String, NodeFixture> requestFixtures) {
        Map<String, NodeFixture> effective = new LinkedHashMap<>();
        draft.nodeFixtures().forEach((nodeId, fixture) ->
                effective.put(nodeId, new NodeFixture(fixture.output(), fixture.expectedInput())));
        if (requestFixtures != null) {
            requestFixtures.forEach((nodeId, fixture) -> {
                if (nodeId != null && !nodeId.isBlank() && fixture != null) {
                    effective.put(nodeId, fixture);
                }
            });
        }
        return effective;
    }

    private static List<VisualDiagnostic> inputAssertionDiagnostics(
            Map<String, NodeFixture> fixtures,
            Map<String, SimulationOperator> simulationOperators) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        fixtures.forEach((nodeId, fixture) -> {
            if (fixture == null || fixture.expectedInput() == null) {
                return;
            }
            SimulationOperator operator = simulationOperators.get(nodeId);
            Object actualInput = operator == null ? null : operator.lastInput();
            if (!Objects.equals(fixture.expectedInput(), actualInput)) {
                diagnostics.add(VisualDiagnostic.error("visual.simulate.inputAssertionMismatch",
                        "Node '%s' input assertion failed: expected %s but observed %s."
                                .formatted(nodeId, fixture.expectedInput(), actualInput),
                        "/fixtures/" + nodeId + "/expectedInput"));
            }
        });
        return diagnostics;
    }

    private static List<VisualDiagnostic> kernelInputMismatchDiagnostics(
            VisualDslRunResponse dynamic,
            Map<String, NodeFixture> fixtures) {
        if (!dynamic.errors().contains("FIXTURE_UNMATCHED")) {
            return List.of();
        }
        return fixtures.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().expectedInput() != null)
                .filter(entry -> {
                    List<VisualNodeExecutionAttempt> attempts = dynamic.nodeAttempts()
                            .getOrDefault(entry.getKey(), List.of());
                    return attempts.isEmpty() || attempts.stream()
                            .anyMatch(attempt -> "FIXTURE_UNMATCHED".equals(attempt.errorType()));
                })
                .map(entry -> {
                    String nodeId = entry.getKey();
                    Object actualInput = dynamic.nodeAttempts().getOrDefault(nodeId, List.of()).stream()
                            .reduce((left, right) -> right)
                            .map(VisualNodeExecutionAttempt::input)
                            .orElse(null);
                    return VisualDiagnostic.error("visual.simulate.inputAssertionMismatch",
                            "Node '%s' input assertion failed: expected %s but observed %s."
                                    .formatted(nodeId, entry.getValue().expectedInput(), actualInput),
                            "/fixtures/" + nodeId + "/expectedInput");
                })
                .toList();
    }

    private static Map<String, Object> effectiveContext(GraphDraft draft, Map<String, Object> context) {
        Map<String, Object> effective = new LinkedHashMap<>(context == null ? Map.of() : context);
        effective.putIfAbsent("tenantId", draft.tenantId());
        effective.putIfAbsent("namespace", draft.namespace());
        return effective;
    }

    private boolean terminalOutputConforms(GraphDraft draft, String outputNodeId, Object output) {
        return draft.nodes().stream()
                .filter(node -> node.id().equals(outputNodeId))
                .findFirst()
                .flatMap(node -> catalog.find(node.operatorRef()))
                .map(operator -> terminalOutputConforms(operator, output))
                .orElse(true);
    }

    private static boolean terminalOutputConforms(OperatorDefinition operator, Object output) {
        List<OperatorDefinition.Port> outputPorts = operator.ports().outputs();
        if (outputPorts.isEmpty()) {
            return true;
        }
        if (outputPorts.size() == 1) {
            OperatorDefinition.Port port = outputPorts.get(0);
            Object candidate = output instanceof Map<?, ?> outputMap && outputMap.containsKey(port.name())
                    ? outputMap.get(port.name())
                    : output;
            return VisualSchemaValidator.validateValue(port.schema(), candidate, "/output").isEmpty();
        }
        if (!(output instanceof Map<?, ?> outputMap)) {
            return false;
        }
        return outputPorts.stream().allMatch(port -> {
            if (!outputMap.containsKey(port.name())) {
                return !port.required();
            }
            return VisualSchemaValidator.validateValue(
                    port.schema(),
                    outputMap.get(port.name()),
                    "/output/" + port.name()
            ).isEmpty();
        });
    }

    private static List<VisualDiagnostic> enforceResourceCaps(GraphDraft draft) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (draft.nodes().size() > MAX_SIMULATION_NODES) {
            diagnostics.add(VisualDiagnostic.error("visual.simulate.nodeCapExceeded",
                    "Simulation supports at most %d nodes but the draft has %d."
                            .formatted(MAX_SIMULATION_NODES, draft.nodes().size()),
                    "/nodes"));
        }
        if (draft.edges().size() > MAX_SIMULATION_EDGES) {
            diagnostics.add(VisualDiagnostic.error("visual.simulate.edgeCapExceeded",
                    "Simulation supports at most %d edges but the draft has %d."
                            .formatted(MAX_SIMULATION_EDGES, draft.edges().size()),
                    "/edges"));
        }
        return diagnostics;
    }

    private static VisualDiagnostic fromCompilerDiagnostic(VisualDslRunResponse.Diagnostic diagnostic) {
        String target = diagnostic.nodeId().isBlank()
                ? ""
                : "/nodes/" + diagnostic.nodeId() + (diagnostic.field().isBlank() ? "" : "/" + diagnostic.field());
        return new VisualDiagnostic(
                diagnostic.level(),
                "bloge.dsl",
                diagnostic.message(),
                target,
                diagnostic.line(),
                diagnostic.column()
        );
    }

    private static VisualGraphSimulationResponse blocked(boolean validated,
                                                         List<VisualDiagnostic> diagnostics,
                                                         List<String> errors,
                                                         String generatedDsl) {
        return executionBlocked(validated, false, diagnostics, errors, generatedDsl, "",
                List.of(), List.of(), 0);
    }

    private static VisualGraphSimulationResponse executionBlocked(boolean validated,
                                                                  boolean compiled,
                                                                  List<VisualDiagnostic> diagnostics,
                                                                  List<String> errors,
                                                                  String generatedDsl,
                                                                  String outputNode,
                                                                  List<String> mockedNodeIds,
                                                                  List<String> realNodeIds,
                                                                  long elapsedMs) {
        return new VisualGraphSimulationResponse(
                validated, compiled, false, "", outputNode, null,
                Map.of(), Map.of(), elapsedMs, Map.of(),
                mockedNodeIds, realNodeIds, false,
                diagnostics, errors, generatedDsl
        );
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return DEFAULT_SIMULATION_RUN_TIMEOUT;
        }
        return timeout;
    }

    private static String rootMessage(ExecutionException ex) {
        return "unknown error";
    }
}
