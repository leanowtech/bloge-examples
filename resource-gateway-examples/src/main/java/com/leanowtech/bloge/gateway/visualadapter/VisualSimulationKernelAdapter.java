package com.leanowtech.bloge.gateway.visualadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionModeHints;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationExecutor;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationPlan;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture.ResourceFidelity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** Adapts a visual simulation plan to the isolated test execution kernel. */
@Component
public final class VisualSimulationKernelAdapter implements VisualSimulationExecutor {

    private static final String PURPOSE = "GRAPH_CONTRACT_TEST";
    private static final String COMPILE_ERROR = "VISUAL_SIMULATION_COMPILE_FAILED";
    private static final String INVALID_INPUT_ERROR = "VISUAL_SIMULATION_INPUT_INVALID";
    private static final String PLACEHOLDER_ERROR = "VISUAL_SIMULATION_PLACEHOLDER_INVOKED";

    private final ObjectMapper objectMapper;
    private final ResourceFixtureRuntime resourceRuntime;

    public VisualSimulationKernelAdapter(ObjectMapper objectMapper) {
        this(objectMapper, (ResourceFixtureRuntime) null);
    }

    /** Creates an adapter with the optional real descriptor fixture runtime. */
    public VisualSimulationKernelAdapter(ObjectMapper objectMapper, ResourceFixtureRuntime resourceRuntime) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.resourceRuntime = resourceRuntime;
    }

    /** Spring wiring seam; absent runtime keeps legacy output-level simulations available. */
    @Autowired
    public VisualSimulationKernelAdapter(ObjectMapper objectMapper,
                                         ObjectProvider<ResourceFixtureRuntime> resourceRuntime) {
        this(objectMapper, resourceRuntime.getIfAvailable());
    }

    @Override
    public VisualDslRunResponse execute(VisualSimulationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        String dsl = plan.generatedDsl();
        String outputNode = plan.selectedOutputNode();
        if (dsl.isBlank() || outputNode.isBlank()) {
            return failure(outputNode, INVALID_INPUT_ERROR);
        }

        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        AtomicInteger placeholderExecutions = new AtomicInteger();
        for (String rewrittenOperatorRef : plan.standins().stream()
                .map(VisualSimulationPlan.Standin::rewrittenOperatorRef)
                .distinct().toList()) {
            registry.registerRaw(rewrittenOperatorRef, placeholderOperator(placeholderExecutions));
        }

        Graph graph;
        String targetFingerprint = ProtocolFingerprint.ofText(dsl);
        FixtureBundle bundle;
        try {
            graph = new GraphLoader(registry).load(dsl);
            List<FixtureRule> rules = plan.standins().stream()
                    .map(VisualSimulationKernelAdapter::ruleFor)
                    .toList();
            bundle = new FixtureBundle(
                    FixtureBundle.SCHEMA_VERSION, "visual-simulation", 1,
                    targetFingerprint, "INTERNAL", null, null, rules, List.of(), Map.of());

            ExecutionModeHints.Builder hints = ExecutionModeHints.builder();
            for (VisualSimulationPlan.Standin standin : plan.standins()) {
                if (standin.resourceFidelity() == ResourceFidelity.PROTOCOL_DERIVED) {
                    hints.descriptorProtocol(siteFor(standin), ruleId(standin));
                } else if (standin.resourceFidelity() == ResourceFidelity.TRANSPORT_LEVEL) {
                    hints.descriptorTransport(siteFor(standin), ruleId(standin));
                } else {
                    hints.schemaStandin(siteFor(standin), ruleId(standin));
                }
            }
            var compiled = new ExecutionControlCompiler(registry, objectMapper)
                    .compileWithExecutionModeHints(
                            graph, bundle, PURPOSE, targetFingerprint, hints.build());
            TestExecutionRequest request = new TestExecutionRequest(
                    graph, new GraphContext(plan.businessContext()), bundle, PURPOSE,
                    targetFingerprint, TestExecutionRequest.FixtureSource.INLINE,
                    Map.of(), false, ResolvedReplayPayloads.empty());
            TestExecutionResult result = new TestRunService(registry, objectMapper, resourceRuntime)
                    .executeCompiled(request, compiled);
            if (placeholderExecutions.get() != 0) {
                return failure(outputNode, PLACEHOLDER_ERROR);
            }
            return toVisualResponse(outputNode, graph, result, plan.standins());
        } catch (AssertionError error) {
            return failure(outputNode, placeholderExecutions.get() == 0
                    ? COMPILE_ERROR : PLACEHOLDER_ERROR);
        } catch (RuntimeException error) {
            return failure(outputNode, COMPILE_ERROR);
        }
    }

    private static FixtureRule ruleFor(VisualSimulationPlan.Standin standin) {
        FixtureRule.Selector selector = standin.resourceFidelity() == ResourceFidelity.OUTPUT_LEVEL
                ? FixtureRule.Selector.node(standin.originalNodeId())
                : FixtureRule.Selector.resource(resourceIdFrom(standin));
        if (standin.expectedInputOptional().isPresent()) {
            selector = selector.matching(new FixtureRule.Match(
                    standin.expectedInput(), Map.of(), List.of(), List.of(), Map.of(), "", Map.of()));
        }
        FixtureRule.Behavior behavior = behaviorFor(standin);
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, ruleId(standin), selector,
                behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule.Behavior behaviorFor(VisualSimulationPlan.Standin standin) {
        if (standin.resourceFidelity() == ResourceFidelity.OUTPUT_LEVEL) {
            return FixtureRule.Behavior.returning(standin.output());
        }
        Object rawBody;
        Object status;
        Object rawHeaders = null;
        if (standin.output() instanceof HttpResourceOutput output) {
            rawBody = output.rawBody();
            status = output.statusCode();
        } else if (standin.output() instanceof Map<?, ?> map) {
            rawBody = map.get("rawBody");
            status = map.get("statusCode");
            rawHeaders = map.get("responseHeaders");
        } else {
            throw new IllegalArgumentException("Raw response evidence is required");
        }
        resourceIdFrom(standin);
        if (!(rawBody instanceof String body) || !(status instanceof Number code)) {
            throw new IllegalArgumentException("Raw response evidence is incomplete");
        }
        if (code.intValue() < 100 || code.intValue() > 599) {
            throw new IllegalArgumentException("Raw response status is invalid");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (rawHeaders instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> { if (key instanceof String k && value != null) headers.put(k, value.toString()); });
        }
        FixtureRule.DoubleBoundary boundary = standin.resourceFidelity() == ResourceFidelity.TRANSPORT_LEVEL
                ? FixtureRule.DoubleBoundary.TRANSPORT : FixtureRule.DoubleBoundary.NODE;
        return FixtureRule.Behavior.protocolResponse(body, code.intValue(), headers, boundary);
    }

    private static String resourceIdFrom(VisualSimulationPlan.Standin standin) {
        if (standin.output() instanceof HttpResourceOutput output
                && output.resourceId() != null && !output.resourceId().isBlank()) {
            return output.resourceId().trim();
        }
        if (!(standin.output() instanceof Map<?, ?> map)
                || !(map.get("resourceId") instanceof String resourceId)
                || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource response evidence requires resourceId");
        }
        return resourceId.trim();
    }

    private static String ruleId(VisualSimulationPlan.Standin standin) {
        return "visual-standin-" + standin.originalNodeId();
    }

    private static String siteFor(VisualSimulationPlan.Standin standin) {
        String kind = standin.resourceFidelity() == ResourceFidelity.OUTPUT_LEVEL
                ? "PRIMARY" : "RESOURCE";
        return "/root/" + escapeJsonPointer(standin.originalNodeId()) + "#" + kind;
    }

    private static String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static Operator<Object, Object> placeholderOperator(AtomicInteger executions) {
        return new Operator<>() {
            @Override
            public Object execute(Object input, OperatorContext context) {
                executions.incrementAndGet();
                throw new AssertionError(PLACEHOLDER_ERROR);
            }

            @Override
            public SideEffectType sideEffectType() {
                return SideEffectType.EXTERNAL_CALL;
            }
        };
    }

    private static VisualDslRunResponse toVisualResponse(String outputNode, Graph graph,
                                                         TestExecutionResult result,
                                                         List<VisualSimulationPlan.Standin> standins) {
        GraphResult graphResult = result.graphResult();
        TestRunEvidence evidence = result.evidence();
        Map<String, Object> results = graphResult == null
                ? Map.of() : new LinkedHashMap<>(graphResult.results().getResults());
        Map<String, String> statuses = graphResult == null
                ? Map.of() : graphResult.statusMap().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().name(),
                                (left, right) -> right, LinkedHashMap::new));
        Object output = graphResult == null
                ? null : graphResult.findOutput(outputNode, Object.class).orElse(null);
        Map<String, Long> nodeElapsedMs = graphResult == null
                ? Map.of() : graphResult.nodeTimings().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().toMillis(),
                                (left, right) -> right, LinkedHashMap::new));
        Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts = evidence == null
                ? Map.of() : evidence.nodeTrace().stream().collect(
                        java.util.stream.Collectors.toMap(
                                TestRunEvidence.NodeTrace::nodeId,
                                trace -> trace.attempts().stream().map(attempt ->
                                        new VisualNodeExecutionAttempt(
                                                attempt.attempt(), attempt.input(), attempt.output(),
                                                attempt.status(), Instant.EPOCH,
                                                attempt.durationMs(), attempt.errorCode(), ""))
                                        .toList(),
                                (left, right) -> right, LinkedHashMap::new));
        Map<String, ResourceFidelity> requested = standins.stream().collect(java.util.stream.Collectors.toMap(
                VisualSimulationPlan.Standin::originalNodeId,
                VisualSimulationPlan.Standin::resourceFidelity, (left, right) -> right));
        Map<String, String> nodeFidelity = evidence == null ? Map.of() : evidence.nodeTrace().stream()
                .collect(java.util.stream.Collectors.toMap(TestRunEvidence.NodeTrace::nodeId,
                        trace -> "SCHEMA_STANDIN".equals(trace.fidelity())
                                && requested.get(trace.nodeId()) == ResourceFidelity.OUTPUT_LEVEL
                                ? "OUTPUT_LEVEL" : trace.fidelity(),
                        (left, right) -> right, LinkedHashMap::new));
        List<String> errors = evidenceErrors(evidence);
        return new VisualDslRunResponse(
                true,
                result.passed() && graphResult != null && graphResult.isSuccess(),
                graph.name(), outputNode, output, results, statuses,
                graphResult == null ? 0 : graphResult.elapsed().toMillis(), nodeElapsedMs,
                nodeAttempts, Map.of(), diagnostics(evidence), errors, null, null,
                com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView.unmanaged(), nodeFidelity);
    }

    private static List<VisualDslRunResponse.Diagnostic> diagnostics(TestRunEvidence evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.diagnostics().stream()
                .map(message -> new VisualDslRunResponse.Diagnostic(
                        "ERROR", message, "", "", -1, -1))
                .toList();
    }

    private static List<String> evidenceErrors(TestRunEvidence evidence) {
        if (evidence == null || evidence.status() == TestRunEvidence.Status.PASSED) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        errors.add(evidence.status().name());
        errors.addAll(evidence.diagnostics());
        return List.copyOf(errors);
    }

    private static VisualDslRunResponse failure(String outputNode, String error) {
        return new VisualDslRunResponse(
                false, false, "", outputNode, null, Map.of(), Map.of(), 0, Map.of(),
                Map.of(), Map.of(), List.of(new VisualDslRunResponse.Diagnostic(
                        "ERROR", error, "", "", -1, -1)), List.of(error), null, null);
    }
}
