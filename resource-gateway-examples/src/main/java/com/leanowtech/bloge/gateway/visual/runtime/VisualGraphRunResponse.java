package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.example.ExampleVisualLayout;
import com.leanowtech.bloge.gateway.example.GatewayDecisionTable;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual graph validation, compilation, and execution response.
 *
 * @param validated whether visual validation had no blocking errors
 * @param compiled whether BLOGE compilation produced a graph
 * @param success whether execution succeeded
 * @param graphName compiled graph name
 * @param outputNode selected output node
 * @param output selected output payload
 * @param results raw node results
 * @param statusMap node execution statuses
 * @param elapsedMs execution time in milliseconds
 * @param diagnostics validation and compilation diagnostics
 * @param errors execution or blocking errors
 * @param layout generated layout
 * @param decisionTable extracted decision table view
 * @param generatedDsl generated BLOGE DSL
 */
public record VisualGraphRunResponse(
        boolean validated,
        boolean compiled,
        boolean success,
        String graphName,
        String outputNode,
        Object output,
        Map<String, Object> results,
        Map<String, String> statusMap,
        long elapsedMs,
        List<VisualDiagnostic> diagnostics,
        List<String> errors,
        ExampleVisualLayout layout,
        GatewayDecisionTable decisionTable,
        String generatedDsl
) {
    /**
     * Creates a response.
     */
    public VisualGraphRunResponse {
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        results = results == null ? Map.of() : new LinkedHashMap<>(results);
        statusMap = statusMap == null ? Map.of() : new LinkedHashMap<>(statusMap);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
    }
}
