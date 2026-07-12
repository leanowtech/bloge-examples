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
                Map.of(), diagnostics, errors, layout, decisionTable);
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
