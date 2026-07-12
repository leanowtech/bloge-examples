package com.leanowtech.bloge.gateway.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.ConditionalEdge;
import com.leanowtech.bloge.core.model.DirectEdge;
import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeKind;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.StreamEdge;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.CompilationDiagnostic;
import com.leanowtech.bloge.dsl.compiler.CompilationResult;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compiles and executes browser-submitted gateway DSL without registering it as
 * a permanent built-in example graph.
 */
@Service
public class DynamicGatewayComposerService {

    private static final int MAX_DSL_CHARS = 16_000;
    private static final double NODE_W = 184;
    private static final double NODE_H = 76;

    private final GraphEngine graphEngine;
    private final GraphLoader graphLoader;
    private final NodeExecutionCaptureInterceptor executionCapture;

    /**
     * Creates the dynamic composer service with the runtime operator registry.
     *
     * @param registry executable BLOGE operator registry
     * @param objectMapper mapper used to coerce visual DSL inputs into Java operator input types
     */
    @Autowired
    public DynamicGatewayComposerService(OperatorRegistry registry, ObjectMapper objectMapper) {
        this.executionCapture = new NodeExecutionCaptureInterceptor();
        this.graphEngine = GraphEngine.builder()
                .registry(InputCoercingOperatorRegistry.wrap(registry, objectMapper))
                .interceptors(List.of(executionCapture))
                .build();
        this.graphLoader = new GraphLoader(registry);
    }

    /**
     * Creates the dynamic composer service with a default object mapper for tests and examples.
     *
     * @param registry executable BLOGE operator registry
     */
    public DynamicGatewayComposerService(OperatorRegistry registry) {
        this(registry, new ObjectMapper().findAndRegisterModules());
    }

    /**
     * Test/backward-compatible constructor for a registry containing only {@code httpResource}.
     *
     * @param httpResourceOperator descriptor-backed resource operator
     */
    public DynamicGatewayComposerService(HttpResourceOperator httpResourceOperator) {
        this(dynamicRegistry(httpResourceOperator));
    }

    DynamicGatewayComposerService(Operator<Object, ?> httpResourceOperator) {
        this(dynamicRegistry(httpResourceOperator));
    }

    /**
     * Compiles and executes one submitted graph.
     *
     * @param request submitted DSL, context, and optional output-node preference
     * @return compilation and execution response
     */
    public DynamicGraphRunResponse run(DynamicGraphRunRequest request) {
        if (request.dsl().isBlank()) {
            return compilationFailure("DSL source must not be blank.");
        }
        if (request.dsl().length() > MAX_DSL_CHARS) {
            return compilationFailure("DSL source exceeds %d characters.".formatted(MAX_DSL_CHARS));
        }

        CompilationResult compilation = graphLoader.loadWithDiagnostics(request.dsl());
        List<DynamicGraphRunResponse.Diagnostic> diagnostics = diagnostics(compilation.diagnostics());
        GatewayDecisionTable decisionTable = DecisionTableDslViewExtractor.extract(request.dsl()).orElse(null);
        if (compilation.hasErrors() || compilation.graph() == null) {
            return new DynamicGraphRunResponse(
                    false,
                    false,
                    "",
                    "",
                    null,
                    Map.of(),
                    Map.of(),
                    0,
                    Map.of(),
                    Map.of(),
                    diagnostics,
                    List.of("Compilation failed."),
                    null,
                    decisionTable
            );
        }

        Graph graph = compilation.graph();
        String outputNode = selectOutputNode(graph, request.outputNode());
        ExampleVisualLayout layout = layoutFor(graph);

        String captureId = UUID.randomUUID().toString();
        executionCapture.begin(captureId);
        GraphResult result;
        Map<String, List<DynamicGraphRunResponse.NodeAttempt>> nodeAttempts;
        try {
            result = graphEngine.execute(graph, contextFrom(request.context(), captureId));
        } catch (RuntimeException ex) {
            nodeAttempts = executionCapture.complete(captureId);
            return new DynamicGraphRunResponse(
                    true,
                    false,
                    graph.name(),
                    outputNode,
                    null,
                    Map.of(),
                    Map.of(),
                    0,
                    Map.of(),
                    nodeAttempts,
                    diagnostics,
                    List.of(message(ex)),
                    layout,
                    decisionTable
            );
        }
        nodeAttempts = executionCapture.complete(captureId);

        Object output = result.findOutput(outputNode, Object.class).orElse(null);
        return new DynamicGraphRunResponse(
                true,
                result.isSuccess(),
                graph.name(),
                outputNode,
                output,
                result.results().getResults(),
                statuses(result),
                result.elapsed().toMillis(),
                nodeElapsedMs(result.nodeTimings()),
                nodeAttempts,
                diagnostics,
                errors(result),
                layout,
                decisionTable
        );
    }

    /**
     * Compiles submitted DSL without executing it.
     *
     * @param dsl submitted DSL
     * @return compiler diagnostics
     */
    public List<DynamicGraphRunResponse.Diagnostic> compileDiagnostics(String dsl) {
        if (dsl == null || dsl.isBlank()) {
            return List.of(diagnostic("ERROR", "DSL source must not be blank."));
        }
        if (dsl.length() > MAX_DSL_CHARS) {
            return List.of(diagnostic("ERROR", "DSL source exceeds %d characters.".formatted(MAX_DSL_CHARS)));
        }
        CompilationResult compilation = graphLoader.loadWithDiagnostics(dsl);
        List<DynamicGraphRunResponse.Diagnostic> diagnostics = new ArrayList<>(
                diagnostics(compilation.diagnostics()));
        if (compilation.graph() == null && diagnostics.stream().noneMatch(DynamicGatewayComposerService::error)) {
            diagnostics.add(diagnostic("ERROR", "Compilation failed."));
        }
        return diagnostics;
    }

    private static OperatorRegistry dynamicRegistry(Operator<Object, ?> httpResourceOperator) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("httpResource", httpResourceOperator);
        return registry;
    }

    private static GraphContext contextFrom(Map<String, Object> values, String captureId) {
        String tenantId = stringValue(values.getOrDefault("tenantId", "demo-tenant"));
        String namespace = stringValue(values.getOrDefault("namespace", "local"));
        GraphContext context = new GraphContext(new TenantContext(tenantId, namespace));
        values.forEach(context::put);
        context.put(NodeExecutionCaptureInterceptor.CAPTURE_ID_CONTEXT_KEY, captureId);
        return context;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String selectOutputNode(Graph graph, String requested) {
        if (!requested.isBlank() && graph.nodes().containsKey(requested)) {
            return requested;
        }
        if (graph.terminalNodes().size() == 1) {
            return graph.terminalNodes().iterator().next();
        }
        List<String> order = graph.topologicalOrder();
        return order.isEmpty() ? "" : order.get(order.size() - 1);
    }

    private static List<DynamicGraphRunResponse.Diagnostic> diagnostics(
            List<CompilationDiagnostic> source) {
        return source.stream()
                .map(item -> new DynamicGraphRunResponse.Diagnostic(
                        item.level().name(),
                        item.message(),
                        item.nodeId(),
                        item.field(),
                        item.line(),
                        item.column()
                ))
                .toList();
    }

    private static List<String> errors(GraphResult result) {
        return result.errors().stream()
                .map(error -> error.nodeId() + ": " + message(error.exception()))
                .toList();
    }

    private static String message(Throwable throwable) {
        if (throwable == null) {
            return "Unknown execution error.";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static Map<String, String> statuses(GraphResult result) {
        Map<String, String> statuses = new LinkedHashMap<>();
        result.statusMap().forEach((node, status) -> statuses.put(node, status.name()));
        return statuses;
    }

    private static Map<String, Long> nodeElapsedMs(Map<String, Duration> nodeTimings) {
        if (nodeTimings == null || nodeTimings.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> timings = new LinkedHashMap<>();
        nodeTimings.forEach((node, duration) -> {
            if (node != null && duration != null) {
                timings.put(node, Math.max(0, duration.toMillis()));
            }
        });
        return timings;
    }

    private static DynamicGraphRunResponse compilationFailure(String message) {
        return new DynamicGraphRunResponse(
                false,
                false,
                "",
                "",
                null,
                Map.of(),
                Map.of(),
                0,
                Map.of(),
                Map.of(),
                List.of(new DynamicGraphRunResponse.Diagnostic("ERROR", message, "", "", -1, -1)),
                List.of(message),
                null,
                null
        );
    }

    private static DynamicGraphRunResponse.Diagnostic diagnostic(String level, String message) {
        return new DynamicGraphRunResponse.Diagnostic(level, message, "", "", -1, -1);
    }

    private static boolean error(DynamicGraphRunResponse.Diagnostic diagnostic) {
        return "ERROR".equalsIgnoreCase(diagnostic.level());
    }

    private static ExampleVisualLayout layoutFor(Graph graph) {
        List<String> order = graph.topologicalOrder();
        if (order.isEmpty()) {
            order = new ArrayList<>(graph.nodes().keySet());
        }

        Map<String, Integer> indexByNode = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            indexByNode.put(order.get(i), i);
        }

        List<ExampleVisualLayout.Node> nodes = new ArrayList<>();
        for (String nodeId : order) {
            NodeSpec spec = graph.nodes().get(nodeId);
            if (spec == null) {
                continue;
            }
            int index = indexByNode.getOrDefault(nodeId, nodes.size());
            nodes.add(new ExampleVisualLayout.Node(
                    nodeId,
                    kind(spec),
                    spec.operatorRef(),
                    label(nodeId),
                    new ExampleVisualLayout.Position(80 + index * 280, 210),
                    new ExampleVisualLayout.Size(NODE_W, NODE_H),
                    null,
                    annotations(spec)
            ));
        }

        List<ExampleVisualLayout.Edge> edges = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            edges.addAll(edgesFor(edge));
        }

        return new ExampleVisualLayout(
                ExampleVisualLayout.SCHEMA_VERSION,
                graph.name(),
                "GRAPH",
                nodes,
                edges,
                List.of(),
                new ExampleVisualLayout.Viewport(0, 0, 1)
        );
    }

    private static String kind(NodeSpec spec) {
        NodeKind kind = spec.metadata() == null ? null : spec.metadata().kind();
        if (kind == NodeKind.DECISION_TABLE) {
            return "decision-table";
        }
        if (kind == NodeKind.TRANSFORM) {
            return "transform";
        }
        if (spec.operatorRef() != null && spec.operatorRef().contains("httpResource")) {
            return "resource";
        }
        return kind == null ? "node" : kind.wireValue();
    }

    private static Map<String, Object> annotations(NodeSpec spec) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("kind", kind(spec));
        if (spec.operatorRef() != null && !spec.operatorRef().isBlank()) {
            annotations.put("operatorRef", spec.operatorRef());
        }
        return annotations;
    }

    private static List<ExampleVisualLayout.Edge> edgesFor(Edge edge) {
        List<ExampleVisualLayout.Edge> result = new ArrayList<>();
        if (edge instanceof DirectEdge direct) {
            result.add(layoutEdge(edge.from(), direct.to(), ""));
        } else if (edge instanceof StreamEdge stream) {
            result.add(layoutEdge(edge.from(), stream.to(), "stream"));
        } else if (edge instanceof ConditionalEdge conditional) {
            for (Edge.Branch branch : conditional.branches()) {
                result.add(layoutEdge(edge.from(), branch.target(), "branch"));
            }
            if (conditional.otherwise() != null && !conditional.otherwise().isBlank()) {
                result.add(layoutEdge(edge.from(), conditional.otherwise(), "otherwise"));
            }
        }
        return result;
    }

    private static ExampleVisualLayout.Edge layoutEdge(String source, String target, String label) {
        return new ExampleVisualLayout.Edge(
                source + "->" + target + ":" + label,
                source,
                target,
                label
        );
    }

    private static String label(String id) {
        String spaced = id.replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < spaced.length(); i++) {
            char ch = spaced.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && Character.isLowerCase(spaced.charAt(i - 1))) {
                result.append(' ');
            }
            result.append(ch);
        }
        if (result.isEmpty()) {
            return id;
        }
        result.setCharAt(0, Character.toUpperCase(result.charAt(0)));
        return result.toString();
    }
}
