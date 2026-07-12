package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.ConditionalEdge;
import com.leanowtech.bloge.core.model.DirectEdge;
import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeError;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.model.StreamEdge;
import com.leanowtech.bloge.core.operator.DecisionTableInput;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeCompleteEvent;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeFailedEvent;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeStartEvent;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeSkippedEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-correlated capture of exact invocations and engine-emitted resilience facts.
 */
final class NodeExecutionCaptureInterceptor implements OperatorInterceptor, ExecutionListener {

    static final String CAPTURE_ID_CONTEXT_KEY = "_blogeEvidenceCaptureId";

    private final ConcurrentHashMap<String, CaptureState> captures = new ConcurrentHashMap<>();
    private final ThreadLocal<InvocationScope> invocationScope = new ThreadLocal<>();

    void begin(String captureId, Graph graph) {
        captures.put(captureId, new CaptureState(graph));
    }

    CapturedExecution complete(String captureId, GraphResult result) {
        CaptureState state = captures.remove(captureId);
        if (state == null) {
            return CapturedExecution.empty();
        }
        return new CapturedExecution(state.orderedAttempts(), state.executionFacts(result));
    }

    @Override
    public Object intercept(OperatorInvocation invocation) throws Exception {
        String captureId = captureId(invocation.operatorContext());
        CaptureState state = captures.get(captureId);
        if (captureId.isBlank() || state == null) {
            return invocation.proceed();
        }
        Instant startedAt = Instant.now();
        invocationScope.set(new InvocationScope(captureId, invocation.nodeId()));
        try {
            Object output = invocation.proceed();
            state.recordAttempt(invocation, startedAt, output, "SUCCESS", "", "");
            return output;
        } catch (Exception exception) {
            state.recordAttempt(invocation, startedAt, null, "FAILED", exception.getClass().getName(),
                    message(exception));
            throw exception;
        } finally {
            invocationScope.remove();
        }
    }

    @Override
    public void onNodeStart(NodeStartEvent event) {
        state(event.ctx()).ifPresent(state -> state.started(event.nodeId(), event.nodeSpec()));
    }

    @Override
    public void onNodeComplete(NodeCompleteEvent event) {
        state(event.ctx()).ifPresent(state -> state.completed(event.nodeId(), event.nodeSpec()));
    }

    @Override
    public void onNodeFailed(NodeFailedEvent event) {
        state(event.ctx()).ifPresent(state -> state.failed(event.nodeId(), event.nodeSpec(), event.error()));
    }

    @Override
    public void onNodeSkipped(NodeSkippedEvent event) {
        stateForNode(event.graphName(), event.nodeId())
                .ifPresent(state -> state.skipped(event.nodeId(), event.reason()));
    }

    @Override
    public void onNodeRetry(String graphName, String nodeId, int attempt, Exception lastError, OperatorContext ctx) {
        state(ctx).ifPresent(state -> state.retry(nodeId, attempt, lastError));
    }

    @Override
    public void onNodeTimeout(String graphName, String nodeId, Duration configuredTimeout) {
        stateFromScope(graphName, nodeId).ifPresent(state -> state.timeout(nodeId, configuredTimeout));
    }

    @Override
    public void onNodeFallback(String graphName, String nodeId, Exception originalError) {
        stateFromScope(graphName, nodeId).ifPresent(state -> state.fallback(nodeId, originalError));
    }

    private java.util.Optional<CaptureState> state(OperatorContext context) {
        return java.util.Optional.ofNullable(captures.get(captureId(context)));
    }

    private java.util.Optional<CaptureState> state(com.leanowtech.bloge.core.context.GraphContext context) {
        Object value = context == null ? null : context.get(CAPTURE_ID_CONTEXT_KEY);
        return java.util.Optional.ofNullable(captures.get(value == null ? "" : String.valueOf(value)));
    }

    private java.util.Optional<CaptureState> stateFromScope(String graphName, String nodeId) {
        InvocationScope scope = invocationScope.get();
        if (scope != null && scope.nodeId().equals(nodeId)) {
            return java.util.Optional.ofNullable(captures.get(scope.captureId()));
        }
        List<CaptureState> candidates = captures.values().stream()
                .filter(state -> state.matchesActive(graphName, nodeId))
                .toList();
        if (candidates.size() == 1) {
            return java.util.Optional.of(candidates.getFirst());
        }
        if (candidates.size() > 1) {
            candidates.forEach(state -> state.markCorrelationAmbiguous(nodeId));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<CaptureState> stateForNode(String graphName, String nodeId) {
        List<CaptureState> candidates = captures.values().stream()
                .filter(state -> state.matchesNode(graphName, nodeId))
                .toList();
        if (candidates.size() == 1) {
            return java.util.Optional.of(candidates.getFirst());
        }
        if (candidates.size() > 1) {
            candidates.forEach(state -> state.markCorrelationAmbiguous(nodeId));
        }
        return java.util.Optional.empty();
    }

    private static String captureId(OperatorContext context) {
        Object value = context == null ? null : context.graphContext().get(CAPTURE_ID_CONTEXT_KEY);
        return value == null ? "" : String.valueOf(value);
    }

    private static Object userFacingInput(Object input) {
        return input instanceof DecisionTableInput table ? table.params() : input;
    }

    private static String message(Throwable throwable) {
        String value = throwable == null ? "" : throwable.getMessage();
        return value == null || value.isBlank()
                ? throwable == null ? "Unknown execution error" : throwable.getClass().getSimpleName()
                : value;
    }

    record CapturedExecution(Map<String, List<DynamicGraphRunResponse.NodeAttempt>> attempts,
                             Map<String, DynamicGraphRunResponse.NodeExecutionFact> facts) {
        static CapturedExecution empty() {
            return new CapturedExecution(Map.of(), Map.of());
        }
    }

    private record InvocationScope(String captureId, String nodeId) {
    }

    private static final class CaptureState {
        private final Graph graph;
        private final ConcurrentHashMap<String, List<DynamicGraphRunResponse.NodeAttempt>> attempts =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MutableNodeFact> facts = new ConcurrentHashMap<>();

        private CaptureState(Graph graph) {
            this.graph = graph;
            if (graph != null) {
                graph.nodes().forEach((nodeId, spec) -> facts.put(nodeId, new MutableNodeFact(spec)));
            }
        }

        private void recordAttempt(OperatorInvocation invocation, Instant startedAt, Object output, String status,
                                   String errorType, String errorMessage) {
            DynamicGraphRunResponse.NodeAttempt attempt = new DynamicGraphRunResponse.NodeAttempt(
                    invocation.operatorContext().retryAttempt(), userFacingInput(invocation.input()), output, status,
                    startedAt, Math.max(0, Duration.between(startedAt, Instant.now()).toMillis()), errorType,
                    errorMessage);
            attempts.computeIfAbsent(invocation.nodeId(), ignored -> java.util.Collections.synchronizedList(
                            new ArrayList<>()))
                    .add(attempt);
        }

        private void started(String nodeId, NodeSpec spec) {
            fact(nodeId, spec).started = true;
        }

        private void completed(String nodeId, NodeSpec spec) {
            MutableNodeFact fact = fact(nodeId, spec);
            fact.completed = true;
            fact.terminal = true;
        }

        private void failed(String nodeId, NodeSpec spec, Exception error) {
            MutableNodeFact fact = fact(nodeId, spec);
            fact.failed = true;
            fact.terminal = true;
            fact.lastErrorType = type(error);
        }

        private void skipped(String nodeId, String reason) {
            MutableNodeFact fact = fact(nodeId, null);
            fact.skipReason = reason == null ? "" : reason;
            if (fact.skipReason.contains("deadline budget exhausted")) {
                fact.event("DEADLINE_EXHAUSTED", 0, null);
            }
        }

        private void retry(String nodeId, int attempt, Exception error) {
            MutableNodeFact fact = fact(nodeId, null);
            fact.observedAttempts = Math.max(fact.observedAttempts, Math.max(1, attempt));
            fact.lastErrorType = type(error);
            fact.event(fact.configuredMaxAttempts > 0 && attempt >= fact.configuredMaxAttempts
                    ? "RETRY_EXHAUSTED" : "RETRY_SCHEDULED", attempt, error);
        }

        private void timeout(String nodeId, Duration configuredTimeout) {
            MutableNodeFact fact = fact(nodeId, null);
            fact.timeoutObserved = true;
            if (configuredTimeout != null) {
                fact.configuredTimeoutMs = Math.max(0, configuredTimeout.toMillis());
                fact.timeoutConfigured = true;
            }
            fact.event("TIMEOUT", Math.max(0, fact.observedAttempts - 1), null);
        }

        private void fallback(String nodeId, Exception error) {
            MutableNodeFact fact = fact(nodeId, null);
            fact.fallbackUsed = true;
            fact.fallbackOriginalErrorType = type(error);
            fact.lastErrorType = type(error);
            fact.event("FALLBACK", Math.max(0, fact.observedAttempts - 1), error);
        }

        private MutableNodeFact fact(String nodeId, NodeSpec spec) {
            return facts.compute(nodeId, (ignored, current) -> {
                if (current == null) {
                    return new MutableNodeFact(spec);
                }
                current.applyPolicy(spec);
                return current;
            });
        }

        private boolean matchesActive(String graphName, String nodeId) {
            if (graph == null || graphName == null || !graph.name().equals(graphName)) {
                return false;
            }
            MutableNodeFact fact = facts.get(nodeId);
            return fact != null && fact.active();
        }

        private boolean matchesNode(String graphName, String nodeId) {
            return graph != null && graphName != null && graph.name().equals(graphName)
                    && graph.nodes().containsKey(nodeId);
        }

        private void markCorrelationAmbiguous(String nodeId) {
            MutableNodeFact fact = facts.get(nodeId);
            if (fact != null) {
                fact.correlationAmbiguous = true;
            }
        }

        private Map<String, List<DynamicGraphRunResponse.NodeAttempt>> orderedAttempts() {
            Map<String, List<DynamicGraphRunResponse.NodeAttempt>> ordered = new LinkedHashMap<>();
            attempts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    ordered.put(entry.getKey(), entry.getValue().stream()
                            .sorted(Comparator.comparingInt(DynamicGraphRunResponse.NodeAttempt::attempt)
                                    .thenComparing(DynamicGraphRunResponse.NodeAttempt::startedAt))
                            .toList()));
            return ordered;
        }

        private Map<String, DynamicGraphRunResponse.NodeExecutionFact> executionFacts(GraphResult result) {
            Map<String, String> statuses = new LinkedHashMap<>();
            if (result != null) {
                result.statusMap().forEach((nodeId, status) -> statuses.put(nodeId, status.name()));
                for (NodeError error : result.errors()) {
                    MutableNodeFact fact = fact(error.nodeId(), graph == null ? null : graph.nodes().get(error.nodeId()));
                    if (fact.lastErrorType.isBlank()) {
                        fact.lastErrorType = type(error.exception());
                    }
                }
            }
            Set<String> nodeIds = new LinkedHashSet<>(facts.keySet());
            nodeIds.addAll(statuses.keySet());
            nodeIds.addAll(attempts.keySet());
            Map<String, DynamicGraphRunResponse.NodeExecutionFact> projected = new LinkedHashMap<>();
            nodeIds.stream().sorted().forEach(nodeId -> {
                MutableNodeFact fact = fact(nodeId, graph == null ? null : graph.nodes().get(nodeId));
                projected.put(nodeId, fact.freeze(statuses.get(nodeId), causes(nodeId, statuses)));
            });
            return projected;
        }

        private List<String> causes(String nodeId, Map<String, String> statuses) {
            if (graph == null || !("CANCELLED".equals(statuses.get(nodeId)) || "SKIPPED".equals(statuses.get(nodeId)))) {
                return List.of();
            }
            List<String> incoming = new ArrayList<>();
            for (Edge edge : graph.edges()) {
                if (edge instanceof DirectEdge direct && nodeId.equals(direct.to())) {
                    incoming.add(edge.from());
                } else if (edge instanceof StreamEdge stream && nodeId.equals(stream.to())) {
                    incoming.add(edge.from());
                } else if (edge instanceof ConditionalEdge conditional
                        && (conditional.branches().stream().anyMatch(branch -> nodeId.equals(branch.target()))
                        || nodeId.equals(conditional.otherwise()))) {
                    incoming.add(edge.from());
                }
            }
            if ("CANCELLED".equals(statuses.get(nodeId))) {
                List<String> failed = incoming.stream().filter(source -> Set.of("FAILED", "CANCELLED", "SKIPPED")
                        .contains(statuses.get(source))).toList();
                return failed.isEmpty() ? List.copyOf(incoming) : failed;
            }
            return List.copyOf(incoming);
        }
    }

    private static final class MutableNodeFact {
        private int configuredMaxAttempts;
        private int observedAttempts;
        private boolean timeoutConfigured;
        private long configuredTimeoutMs;
        private boolean timeoutObserved;
        private boolean fallbackConfigured;
        private boolean fallbackUsed;
        private String fallbackStrategy = "NONE";
        private String fallbackOriginalErrorType = "";
        private String lastErrorType = "";
        private boolean started;
        private boolean completed;
        private boolean failed;
        private boolean terminal;
        private boolean correlationAmbiguous;
        private String skipReason = "";
        private int eventSequence;
        private final List<DynamicGraphRunResponse.Event> events = new ArrayList<>();

        private MutableNodeFact(NodeSpec spec) {
            applyPolicy(spec);
        }

        private synchronized void applyPolicy(NodeSpec spec) {
            if (spec == null) {
                return;
            }
            ResilienceConfig resilience = spec.resilience();
            configuredMaxAttempts = resilience.retryAttempts() + 1;
            timeoutConfigured = resilience.hasTimeout();
            configuredTimeoutMs = timeoutConfigured ? Math.max(0, resilience.timeout().toMillis()) : 0;
            fallbackConfigured = resilience.hasFallback();
            fallbackStrategy = resilience.fallbackFunction() != null
                    ? "EXCEPTION_FUNCTION"
                    : resilience.fallback() != null ? "FIXED_VALUE" : "NONE";
        }

        private synchronized void event(String eventType, int attempt, Exception error) {
            events.add(new DynamicGraphRunResponse.Event(++eventSequence, eventType, Instant.now(), attempt,
                    type(error)));
        }

        private synchronized boolean active() {
            return started && !terminal;
        }

        private synchronized DynamicGraphRunResponse.NodeExecutionFact freeze(String runtimeStatus,
                                                                               List<String> causedBy) {
            int attempts = Math.max(observedAttempts, started ? 1 : 0);
            String status;
            String reason;
            String source;
            switch (runtimeStatus == null ? "UNKNOWN" : runtimeStatus) {
                case "COMPLETED" -> {
                    status = fallbackUsed ? "FALLBACK" : "SUCCESS";
                    reason = fallbackUsed ? "FALLBACK_SUCCEEDED" : "NONE";
                    source = fallbackUsed ? "ENGINE_RESILIENCE_EVENT" : "ENGINE_STATUS";
                }
                case "FAILED" -> {
                    status = timeoutObserved ? "TIMEOUT" : "FAILED";
                    boolean retryExhausted = configuredMaxAttempts > 1 && attempts >= configuredMaxAttempts;
                    reason = timeoutObserved ? "NODE_TIMEOUT" : retryExhausted ? "RETRY_EXHAUSTED" : "OPERATOR_ERROR";
                    source = timeoutObserved ? "ENGINE_RESILIENCE_EVENT" : "ENGINE_STATUS";
                }
                case "SKIPPED" -> {
                    status = "SKIPPED";
                    reason = "BRANCH_NOT_TAKEN";
                    source = "TOPOLOGY_DERIVATION";
                }
                case "CANCELLED" -> {
                    status = "CANCELLED";
                    boolean deadlineExhausted = skipReason.contains("deadline budget exhausted");
                    reason = deadlineExhausted ? "DEADLINE_EXHAUSTED" : "UPSTREAM_FAILED";
                    source = deadlineExhausted ? "ENGINE_ADMISSION" : "TOPOLOGY_DERIVATION";
                }
                case "SUSPENDED" -> {
                    status = "PARTIAL";
                    reason = "SUSPENDED_WAITING";
                    source = "ENGINE_STATUS";
                }
                case "PENDING_MANUAL" -> {
                    status = "PARTIAL";
                    reason = "MANUAL_INTERVENTION_REQUIRED";
                    source = "ENGINE_STATUS";
                }
                case "STREAMING" -> {
                    status = "PARTIAL";
                    reason = "STREAM_IN_PROGRESS";
                    source = "ENGINE_STATUS";
                }
                default -> {
                    status = completed ? "SUCCESS" : failed ? "FAILED" : "UNKNOWN";
                    reason = completed ? "NONE" : failed ? "OPERATOR_ERROR" : "STATUS_NOT_CAPTURED";
                    source = completed || failed ? "ENGINE_LIFECYCLE_EVENT" : "NOT_CAPTURED";
                }
            }
            if (correlationAmbiguous && (fallbackConfigured || timeoutConfigured)) {
                reason = "RESILIENCE_EVENT_CORRELATION_AMBIGUOUS";
                source = "ENGINE_STATUS_WITH_EVENT_GAP";
            }
            boolean exhausted = configuredMaxAttempts > 1 && attempts >= configuredMaxAttempts;
            return new DynamicGraphRunResponse.NodeExecutionFact(status, reason, source, causedBy,
                    new DynamicGraphRunResponse.Retry(configuredMaxAttempts, attempts, exhausted, lastErrorType),
                    new DynamicGraphRunResponse.Timeout(timeoutConfigured, configuredTimeoutMs, timeoutObserved),
                    new DynamicGraphRunResponse.Fallback(fallbackConfigured, fallbackUsed, fallbackStrategy,
                            fallbackOriginalErrorType),
                    "NOT_CAPTURED", List.copyOf(events));
        }
    }

    private static String type(Throwable error) {
        return error == null ? "" : error.getClass().getName();
    }
}
