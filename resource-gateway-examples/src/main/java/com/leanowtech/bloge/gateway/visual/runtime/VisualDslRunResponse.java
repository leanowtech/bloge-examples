package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result returned by a BLOGE DSL execution adapter to the visual orchestration core.
 *
 * <p>This mirrors the data the canvas needs from an executable run while keeping visual services
 * independent from the resource-gateway showcase DTOs.</p>
 *
 * @param compiled whether DSL compilation produced a graph
 * @param success whether graph execution completed successfully
 * @param graphName compiled graph name
 * @param outputNode selected output node
 * @param output selected output payload
 * @param results raw node outputs keyed by node id
 * @param statusMap node execution status keyed by node id
 * @param elapsedMs whole-run elapsed time in milliseconds
 * @param nodeElapsedMs per-node elapsed times in milliseconds
 * @param nodeAttempts exact operator invocation attempts keyed by node id
 * @param diagnostics compiler diagnostics projected into a visual-owned shape
 * @param errors execution error messages
 * @param layout generated visual layout, when the adapter can provide one
 * @param decisionTable extracted decision-table display metadata, when available
 */
public record VisualDslRunResponse(
        boolean compiled,
        boolean success,
        String graphName,
        String outputNode,
        Object output,
        Map<String, Object> results,
        Map<String, String> statusMap,
        long elapsedMs,
        Map<String, Long> nodeElapsedMs,
        Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
        List<Diagnostic> diagnostics,
        List<String> errors,
        VisualRunLayout layout,
        VisualDecisionTable decisionTable
) {
    /**
     * Creates a normalized adapter response.
     */
    public VisualDslRunResponse {
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        results = results == null ? Map.of() : new LinkedHashMap<>(results);
        statusMap = statusMap == null ? Map.of() : new LinkedHashMap<>(statusMap);
        nodeElapsedMs = nodeElapsedMs == null ? Map.of() : new LinkedHashMap<>(nodeElapsedMs);
        nodeAttempts = immutableAttempts(nodeAttempts);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Backward-compatible constructor for adapters that do not yet expose invocation attempts. */
    public VisualDslRunResponse(boolean compiled,
                                boolean success,
                                String graphName,
                                String outputNode,
                                Object output,
                                Map<String, Object> results,
                                Map<String, String> statusMap,
                                long elapsedMs,
                                Map<String, Long> nodeElapsedMs,
                                List<Diagnostic> diagnostics,
                                List<String> errors,
                                VisualRunLayout layout,
                                VisualDecisionTable decisionTable) {
        this(compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs, nodeElapsedMs,
                Map.of(), diagnostics, errors, layout, decisionTable);
    }

    private static Map<String, List<VisualNodeExecutionAttempt>> immutableAttempts(
            Map<String, List<VisualNodeExecutionAttempt>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<VisualNodeExecutionAttempt>> copy = new LinkedHashMap<>();
        source.forEach((nodeId, attempts) -> copy.put(nodeId, attempts == null ? List.of() : List.copyOf(attempts)));
        return copy;
    }

    /**
     * Compiler diagnostic projected into the visual runtime boundary.
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
         * Creates a normalized diagnostic payload.
         */
        public Diagnostic {
            level = level == null ? "INFO" : level;
            message = message == null ? "" : message;
            nodeId = nodeId == null ? "" : nodeId;
            field = field == null ? "" : field;
        }
    }
}
