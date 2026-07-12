package com.leanowtech.bloge.gateway.example;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic graph compilation and execution response for the browser composer.
 *
 * @param compiled whether DSL compilation produced a graph
 * @param success whether graph execution completed successfully
 * @param graphName compiled graph name
 * @param outputNode selected output node
 * @param output selected output payload
 * @param results raw node outputs keyed by node id
 * @param statusMap node execution status keyed by node id
 * @param elapsedMs execution time in milliseconds
 * @param nodeElapsedMs per-node execution time in milliseconds
 * @param nodeAttempts exact operator invocation attempts keyed by node id
 * @param nodeExecutionFacts structured engine-observed execution semantics keyed by node id
 * @param diagnostics compiler diagnostics
 * @param errors execution error messages
 * @param layout generated visual layout for the submitted graph
 * @param decisionTable extracted decision-table display metadata, when present
 */
public record DynamicGraphRunResponse(
        boolean compiled,
        boolean success,
        String graphName,
        String outputNode,
        Object output,
        Map<String, Object> results,
        Map<String, String> statusMap,
        long elapsedMs,
        Map<String, Long> nodeElapsedMs,
        Map<String, List<NodeAttempt>> nodeAttempts,
        Map<String, NodeExecutionFact> nodeExecutionFacts,
        List<Diagnostic> diagnostics,
        List<String> errors,
        ExampleVisualLayout layout,
        GatewayDecisionTable decisionTable
) {
    /**
     * Creates a response payload.
     */
    public DynamicGraphRunResponse {
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        results = results == null ? Map.of() : new LinkedHashMap<>(results);
        statusMap = statusMap == null ? Map.of() : new LinkedHashMap<>(statusMap);
        nodeElapsedMs = nodeElapsedMs == null ? Map.of() : new LinkedHashMap<>(nodeElapsedMs);
        nodeAttempts = immutableAttempts(nodeAttempts);
        nodeExecutionFacts = nodeExecutionFacts == null ? Map.of() : new LinkedHashMap<>(nodeExecutionFacts);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * Backward-compatible constructor for callers that do not expose per-node timings.
     */
    public DynamicGraphRunResponse(boolean compiled,
                                   boolean success,
                                   String graphName,
                                   String outputNode,
                                   Object output,
                                   Map<String, Object> results,
                                   Map<String, String> statusMap,
                                   long elapsedMs,
                                   List<Diagnostic> diagnostics,
                                   List<String> errors,
                                   ExampleVisualLayout layout,
                                   GatewayDecisionTable decisionTable) {
        this(compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs, Map.of(),
                Map.of(), Map.of(), diagnostics, errors, layout, decisionTable);
    }

    /** Backward-compatible constructor for callers using the pre-semantics execution shape. */
    public DynamicGraphRunResponse(boolean compiled,
                                   boolean success,
                                   String graphName,
                                   String outputNode,
                                   Object output,
                                   Map<String, Object> results,
                                   Map<String, String> statusMap,
                                   long elapsedMs,
                                   Map<String, Long> nodeElapsedMs,
                                   Map<String, List<NodeAttempt>> nodeAttempts,
                                   List<Diagnostic> diagnostics,
                                   List<String> errors,
                                   ExampleVisualLayout layout,
                                   GatewayDecisionTable decisionTable) {
        this(compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs, nodeElapsedMs,
                nodeAttempts, Map.of(), diagnostics, errors, layout, decisionTable);
    }

    private static Map<String, List<NodeAttempt>> immutableAttempts(Map<String, List<NodeAttempt>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<NodeAttempt>> copy = new LinkedHashMap<>();
        source.forEach((nodeId, attempts) -> copy.put(nodeId, attempts == null ? List.of() : List.copyOf(attempts)));
        return copy;
    }

    /** Exact input/output facts captured around one operator invocation. */
    public record NodeAttempt(int attempt, Object input, Object output, String status, Instant startedAt,
                              long elapsedMs, String errorType, String errorMessage) {
        public NodeAttempt {
            attempt = Math.max(0, attempt);
            status = status == null || status.isBlank() ? "UNKNOWN" : status;
            startedAt = startedAt == null ? Instant.EPOCH : startedAt;
            elapsedMs = Math.max(0, elapsedMs);
            errorType = errorType == null ? "" : errorType;
            errorMessage = errorMessage == null ? "" : errorMessage;
        }
    }

    /** Structured node-level policy and failure semantics observed by the BLOGE runtime. */
    public record NodeExecutionFact(String status, String reasonCode, String observationSource,
                                    List<String> causedByNodeIds, Retry retry, Timeout timeout,
                                    Fallback fallback, String sideEffectOutcome, List<Event> events) {
        public NodeExecutionFact {
            status = normalized(status, "UNKNOWN");
            reasonCode = normalized(reasonCode, "STATUS_NOT_CAPTURED");
            observationSource = normalized(observationSource, "NOT_CAPTURED");
            causedByNodeIds = causedByNodeIds == null ? List.of() : List.copyOf(causedByNodeIds);
            sideEffectOutcome = normalized(sideEffectOutcome, "NOT_CAPTURED");
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public record Retry(int configuredMaxAttempts, int observedAttempts, boolean exhausted,
                        String lastErrorType) {
        public Retry {
            configuredMaxAttempts = Math.max(0, configuredMaxAttempts);
            observedAttempts = Math.max(0, observedAttempts);
            lastErrorType = lastErrorType == null ? "" : lastErrorType;
        }
    }

    public record Timeout(boolean configured, long configuredTimeoutMs, boolean observed) {
        public Timeout {
            configuredTimeoutMs = Math.max(0, configuredTimeoutMs);
        }
    }

    public record Fallback(boolean configured, boolean used, String strategy, String originalErrorType) {
        public Fallback {
            strategy = normalized(strategy, configured ? "CONFIGURED" : "NONE");
            originalErrorType = originalErrorType == null ? "" : originalErrorType;
        }
    }

    public record Event(int sequence, String type, Instant observedAt, int attempt, String errorType) {
        public Event {
            sequence = Math.max(0, sequence);
            type = normalized(type, "UNKNOWN");
            observedAt = observedAt == null ? Instant.EPOCH : observedAt;
            attempt = Math.max(0, attempt);
            errorType = errorType == null ? "" : errorType;
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Compiler diagnostic projected for JSON clients.
     *
     * @param level diagnostic severity
     * @param message diagnostic message
     * @param nodeId related node id, when available
     * @param field related field, when available
     * @param line source line, or {@code -1} when unavailable
     * @param column source column, or {@code -1} when unavailable
     */
    public record Diagnostic(
            String level,
            String message,
            String nodeId,
            String field,
            int line,
            int column
    ) {
        /**
         * Creates a diagnostic payload.
         */
        public Diagnostic {
            level = level == null ? "INFO" : level;
            message = message == null ? "" : message;
            nodeId = nodeId == null ? "" : nodeId;
            field = field == null ? "" : field;
        }
    }
}
