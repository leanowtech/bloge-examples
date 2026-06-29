package com.leanowtech.bloge.gateway.example;

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
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
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
