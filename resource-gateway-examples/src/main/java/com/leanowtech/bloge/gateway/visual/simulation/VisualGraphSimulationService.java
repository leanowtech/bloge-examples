package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.example.DynamicGatewayComposerService;
import com.leanowtech.bloge.gateway.example.DynamicGraphRunRequest;
import com.leanowtech.bloge.gateway.example.DynamicGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * <p>Simulation is bounded by node and edge caps; sample synthesis is itself depth/size bounded by
 * {@link JsonSchemaSampleGenerator}; and because HTTP/resource operators are mocked, simulation performs
 * no outbound calls (no SSRF surface).</p>
 */
@Service
public class VisualGraphSimulationService {

    /** Prefix for the per-node native simulation stand-in operator reference. */
    static final String SIM_OPERATOR_PREFIX = "__sim_";

    /** Maximum number of nodes a single simulation may contain (D21 resource guard). */
    static final int MAX_SIMULATION_NODES = 200;

    /** Maximum number of edges a single simulation may contain (D21 resource guard). */
    static final int MAX_SIMULATION_EDGES = 400;

    /** Lowering modes that emit a real, pure BLOGE DSL primitive rather than an operator invocation. */
    private static final Set<String> PRIMITIVE_LOWERING_MODES = Set.of("transform", "branch");

    /** Operator references that emit a real, pure BLOGE DSL primitive rather than an operator invocation. */
    private static final Set<String> PRIMITIVE_OPERATOR_REFS = Set.of("bloge:transform", "bloge:decisionTable");

    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;
    private final JsonSchemaSampleGenerator sampleGenerator;

    /**
     * @param validator visual draft validator (reused; the action-readiness run gate is intentionally
     *                  not consulted so design-only graphs can be simulated)
     * @param catalog visual operator catalog used for classification and schema lookup
     * @param sampleGenerator deterministic JSON Schema sample generator
     */
    public VisualGraphSimulationService(GraphDraftValidator validator,
                                        VisualOperatorCatalog catalog,
                                        JsonSchemaSampleGenerator sampleGenerator) {
        this.validator = validator;
        this.catalog = catalog;
        this.sampleGenerator = sampleGenerator;
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
     * @param fixtures request-scoped per-node output pins keyed by node id; these override any persisted draft fixture
     * @return the simulation result, including which nodes were mocked vs executed for real
     */
    public VisualGraphSimulationResponse simulate(GraphDraft draft,
                                                  Map<String, Object> context,
                                                  String outputNode,
                                                  Map<String, NodeFixture> fixtures) {
        if (draft == null) {
            return blocked(false, List.of(VisualDiagnostic.error("visual.simulate.draftMissing",
                    "Graph draft is required.", "/")), List.of("Graph draft is required."), "");
        }
        Map<String, Object> fixtureOutputs = fixtureOutputs(draft, fixtures);
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
            boolean pinned = fixtureOutputs.containsKey(node.id());
            if (!pinned && isRealPrimitive(operator.get())) {
                realNodeIds.add(node.id());
                simulationNodes.add(node);
            } else {
                String simulationRef = SIM_OPERATOR_PREFIX + node.id();
                mockedNodeIds.add(node.id());
                Object mockOutput = pinned
                        ? fixtureOutputs.get(node.id())
                        : sampleGenerator.generate(firstOutputSchema(operator.get()));
                mockOutputsByNodeId.put(node.id(), mockOutput);
                syntheticDefinitions.put(simulationRef,
                        syntheticNativeDefinition(simulationRef, operator.get()));
                simulationNodes.add(rewriteToSimulation(node, simulationRef));
            }
        }

        GraphDraft simulationDraft = withNodes(draft, simulationNodes);
        SimulationOperatorCatalog simulationCatalog = new SimulationOperatorCatalog(catalog, syntheticDefinitions);
        DslGenerationResult generated = new GraphDraftDslGenerator(simulationCatalog).generate(simulationDraft);
        diagnostics.addAll(generated.diagnostics());
        if (!generated.generated()) {
            return blocked(true, diagnostics, List.of("Simulation DSL generation failed."), generated.dsl());
        }

        DefaultOperatorRegistry simulationRegistry = new DefaultOperatorRegistry();
        mockOutputsByNodeId.forEach((nodeId, output) -> simulationRegistry.register(
                SIM_OPERATOR_PREFIX + nodeId, SimulationOperator.returning(nodeId, output)));

        Map<String, Object> effectiveContext = effectiveContext(draft, context);
        String selectedOutputNode = outputNode == null || outputNode.isBlank()
                ? draft.output().nodeId()
                : outputNode;

        DynamicGraphRunResponse dynamic = new DynamicGatewayComposerService(simulationRegistry)
                .run(new DynamicGraphRunRequest(generated.dsl(), effectiveContext, selectedOutputNode));
        for (DynamicGraphRunResponse.Diagnostic diagnostic : dynamic.diagnostics()) {
            diagnostics.add(fromCompilerDiagnostic(diagnostic));
        }

        boolean terminalConforms = terminalOutputConforms(draft, dynamic.outputNode(), dynamic.output());

        return new VisualGraphSimulationResponse(
                true,
                dynamic.compiled(),
                dynamic.success(),
                dynamic.graphName(),
                dynamic.outputNode(),
                dynamic.output(),
                dynamic.results(),
                dynamic.statusMap(),
                dynamic.elapsedMs(),
                dynamic.nodeElapsedMs(),
                mockedNodeIds,
                realNodeIds,
                terminalConforms,
                diagnostics,
                dynamic.errors(),
                generated.dsl()
        );
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

    private static Map<String, Object> fixtureOutputs(GraphDraft draft, Map<String, NodeFixture> requestFixtures) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        draft.nodeFixtures().forEach((nodeId, fixture) -> outputs.put(nodeId, fixture.output()));
        if (requestFixtures != null) {
            requestFixtures.forEach((nodeId, fixture) -> {
                if (nodeId != null && !nodeId.isBlank() && fixture != null) {
                    outputs.put(nodeId, fixture.output());
                }
            });
        }
        return outputs;
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
                .map(operator -> VisualSchemaValidator
                        .validateValue(firstOutputSchema(operator), output, "/output").isEmpty())
                .orElse(true);
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

    private static VisualDiagnostic fromCompilerDiagnostic(DynamicGraphRunResponse.Diagnostic diagnostic) {
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
        return new VisualGraphSimulationResponse(
                validated, false, false, "", "", null,
                Map.of(), Map.of(), 0, Map.of(),
                List.of(), List.of(), false,
                diagnostics, errors, generatedDsl
        );
    }
}
