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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final DynamicRunControlManager runControls;

    /**
     * Creates the dynamic composer service with the runtime operator registry.
     *
     * @param registry executable BLOGE operator registry
     * @param objectMapper mapper used to coerce visual DSL inputs into Java operator input types
     */
    public DynamicGatewayComposerService(OperatorRegistry registry, ObjectMapper objectMapper) {
        this(registry, objectMapper, new InMemoryDynamicRunControlRepository());
    }

    /** Creates the service with the default evidence-finalization reserve. */
    public DynamicGatewayComposerService(OperatorRegistry registry,
                                         ObjectMapper objectMapper,
                                         DynamicRunControlRepository runControlRepository) {
        this(registry, objectMapper, runControlRepository, 100);
    }

    /** Creates the Spring service with durable control and a configurable finalization reserve. */
    @Autowired
    public DynamicGatewayComposerService(OperatorRegistry registry,
                                         ObjectMapper objectMapper,
                                         DynamicRunControlRepository runControlRepository,
                                         @Value("${resource-gateway.run-control.finalization-reserve-ms:100}")
                                         long finalizationReserveMs) {
        this.executionCapture = new NodeExecutionCaptureInterceptor();
        this.runControls = new DynamicRunControlManager(runControlRepository,
                Duration.ofMillis(finalizationReserveMs));
        this.graphEngine = GraphEngine.builder()
                .registry(InputCoercingOperatorRegistry.wrap(registry, objectMapper))
                .interceptors(List.of(runControls, executionCapture))
                .listeners(List.of(executionCapture))
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
        DynamicRunControlManager.Registration registration = runControls.begin(request.runIntent());
        if (registration.rejected()) {
            return new DynamicGraphRunResponse(
                    true, false, graph.name(), outputNode, null, Map.of(), Map.of(), 0,
                    Map.of(), Map.of(), Map.of(), diagnostics, List.of(registration.rejection()), layout,
                    decisionTable, runControls.view(registration));
        }

        String captureId = UUID.randomUUID().toString();
        executionCapture.begin(captureId, graph);
        GraphContext context = contextFrom(request.context(), captureId);
        ExecutionOutcome outcome = execute(graph, context, registration);
        GraphResult result = outcome.result();
        NodeExecutionCaptureInterceptor.CapturedExecution captured = executionCapture.complete(captureId, result, context);
        DynamicRunControlView control = runControls.view(registration);
        if (result == null) {
            String error = outcome.terminationUnconfirmed()
                    ? "Controlled run termination was not confirmed before the cancellation grace expired."
                    : message(outcome.failure());
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
                    captured.attempts(),
                    captured.facts(),
                    diagnostics,
                    List.of(error),
                    layout,
                    decisionTable,
                    control
            );
        }

        Object output = result.findOutput(outputNode, Object.class).orElse(null);
        boolean controlledSuccess = result.isSuccess() && "SUCCEEDED".equals(control.status())
                || result.isSuccess() && "UNMANAGED".equals(control.status());
        List<String> runErrors = new ArrayList<>(errors(result));
        if ("CANCELLED".equals(control.status())) {
            runErrors.add("Run was cancelled by a fenced user command.");
        } else if ("TIMED_OUT".equals(control.status())) {
            runErrors.add("Graph deadline elapsed and cooperative termination was confirmed.");
        }
        return new DynamicGraphRunResponse(
                true,
                controlledSuccess,
                graph.name(),
                outputNode,
                output,
                result.results().getResults(),
                statuses(result),
                result.elapsed().toMillis(),
                nodeElapsedMs(result.nodeTimings()),
                captured.attempts(),
                captured.facts(),
                diagnostics,
                runErrors,
                layout,
                decisionTable,
                control
        );
    }

    /** Returns the lifecycle view for a caller-addressed controlled run. */
    public DynamicRunControlResult runControl(String requestId, String fencingToken) {
        return runControls.find(requestId, fencingToken);
    }

    /** Requests cooperative cancellation using the immutable run fencing token. */
    public DynamicRunControlResult cancel(DynamicRunControlCommand command) {
        return runControls.cancel(command);
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

    private ExecutionOutcome execute(Graph graph,
                                     GraphContext context,
                                     DynamicRunControlManager.Registration registration) {
        if (!registration.managed()) {
            try {
                return new ExecutionOutcome(graphEngine.execute(graph, context), null, false);
            } catch (RuntimeException failure) {
                return new ExecutionOutcome(null, failure, false);
            }
        }

        CompletableFuture<GraphResult> completion = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().unstarted(() -> {
            GraphResult result = null;
            Throwable failure = null;
            try {
                result = graphEngine.execute(graph, context);
                runControls.observeExecutionId(registration, context);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                runControls.observeExecutionId(registration, context);
                runControls.complete(registration, result, failure);
            }
            if (failure == null) {
                completion.complete(result);
            } else {
                completion.completeExceptionally(failure);
            }
        });
        runControls.attach(registration, owner, context);
        owner.start();
        return awaitControlled(completion, registration);
    }

    private ExecutionOutcome awaitControlled(CompletableFuture<GraphResult> completion,
                                             DynamicRunControlManager.Registration registration) {
        while (true) {
            DynamicRunControlView view = runControls.view(registration);
            Instant now = Instant.now();
            if (view.deadlineAt() != null && !now.isBefore(view.deadlineAt())
                    && "RUNNING".equals(view.status())) {
                view = runControls.deadline(registration);
            }
            if (stopRequested(view)) {
                Instant confirmationStartedAt = view.cancelRequestedAt() == null
                        ? view.terminalAt()
                        : view.cancelRequestedAt();
                long elapsed = confirmationStartedAt == null ? 0
                        : Math.max(0, Duration.between(confirmationStartedAt, now).toMillis());
                if (elapsed >= runControls.cancellationGraceMs(registration)) {
                    if (!"TERMINATION_UNCONFIRMED".equals(view.status())) {
                        runControls.terminationUnconfirmed(registration);
                    }
                    return new ExecutionOutcome(null, null, true);
                }
            }
            boolean awaitingOperatorDrain = completion.isDone()
                    && "TERMINATION_UNCONFIRMED".equals(view.status())
                    && !view.terminationConfirmed();
            if (completion.isDone() && !awaitingOperatorDrain) {
                return completedOutcome(completion);
            }
            long waitMs = waitMillis(view, now, runControls.cancellationGraceMs(registration));
            if (awaitingOperatorDrain) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new ExecutionOutcome(null, interrupted, false);
                }
                continue;
            }
            try {
                GraphResult result = completion.get(waitMs, TimeUnit.MILLISECONDS);
                DynamicRunControlView afterCompletion = runControls.view(registration);
                if ("TERMINATION_UNCONFIRMED".equals(afterCompletion.status())
                        && !afterCompletion.terminationConfirmed()) {
                    continue;
                }
                return new ExecutionOutcome(result, null, false);
            } catch (TimeoutException ignored) {
                // Re-evaluate deadline, cancellation state, and grace on the next iteration.
            } catch (ExecutionException failure) {
                DynamicRunControlView afterCompletion = runControls.view(registration);
                if ("TERMINATION_UNCONFIRMED".equals(afterCompletion.status())
                        && !afterCompletion.terminationConfirmed()) {
                    continue;
                }
                return new ExecutionOutcome(null, failure.getCause(), false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new ExecutionOutcome(null, interrupted, false);
            }
        }
    }

    private static ExecutionOutcome completedOutcome(CompletableFuture<GraphResult> completion) {
        try {
            return new ExecutionOutcome(completion.getNow(null), null, false);
        } catch (java.util.concurrent.CompletionException failure) {
            return new ExecutionOutcome(null, failure.getCause(), false);
        }
    }

    private static boolean stopRequested(DynamicRunControlView view) {
        return "CANCEL_REQUESTED".equals(view.status())
                || "TIMING_OUT".equals(view.status())
                || "TERMINATION_UNCONFIRMED".equals(view.status());
    }

    private static long waitMillis(DynamicRunControlView view, Instant now, long graceMs) {
        long wait = 50;
        if (view.deadlineAt() != null && now.isBefore(view.deadlineAt())) {
            wait = Math.min(wait, Math.max(1, Duration.between(now, view.deadlineAt()).toMillis()));
        }
        if (stopRequested(view) && view.cancelRequestedAt() != null) {
            long elapsed = Math.max(0, Duration.between(view.cancelRequestedAt(), now).toMillis());
            wait = Math.min(wait, Math.max(1, graceMs - elapsed));
        }
        return Math.max(1, wait);
    }

    private record ExecutionOutcome(GraphResult result, Throwable failure, boolean terminationUnconfirmed) {
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
