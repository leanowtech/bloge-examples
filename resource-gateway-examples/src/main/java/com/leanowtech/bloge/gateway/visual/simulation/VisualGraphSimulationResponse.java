package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a visual graph mock-run (simulate).
 *
 * <p>The response distinguishes {@link #mockedNodeIds() mocked} nodes (operator-invoking nodes whose
 * output was synthesized from the declared schema) from {@link #realNodeIds() real} nodes (DSL-primitive
 * transform/decision/branch nodes that executed for real). This distinction is a first-class trust
 * signal (decision D15): synthesized outputs must never be mistaken for real results.</p>
 *
 * @param validated whether visual validation had no blocking errors
 * @param compiled whether the simulation DSL compiled to a graph
 * @param success whether the simulated execution succeeded
 * @param graphName compiled graph name
 * @param outputNode selected output node
 * @param output selected output payload
 * @param results raw node results keyed by node id
 * @param statusMap node execution statuses keyed by node id
 * @param elapsedMs execution time in milliseconds
 * @param nodeElapsedMs per-node execution time in milliseconds
 * @param mockedNodeIds ids of nodes whose output was synthesized from schema (operator-invoking nodes)
 * @param realNodeIds ids of nodes that executed for real (DSL-primitive nodes)
 * @param terminalOutputConforms whether the terminal output conforms to the output node's declared schema
 * @param diagnostics validation, compilation, and execution diagnostics
 * @param errors execution or blocking errors
 * @param generatedDsl the simulation DSL that was executed
 * @param nodeFidelity evidence-derived fixture fidelity by node
 */
public record VisualGraphSimulationResponse(
        boolean validated,
        boolean compiled,
        boolean success,
        String graphName,
        String outputNode,
        Object output,
        Map<String, Object> results,
        Map<String, String> statusMap,
        long elapsedMs,
        Map<String, Long> nodeElapsedMs,
        List<String> mockedNodeIds,
        List<String> realNodeIds,
        boolean terminalOutputConforms,
        List<VisualDiagnostic> diagnostics,
        List<String> errors,
        String generatedDsl,
        Map<String, String> nodeFidelity
) {
    /**
     * Normalizes nullable collections to immutable, non-null values.
     */
    public VisualGraphSimulationResponse {
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        results = results == null ? Map.of() : new LinkedHashMap<>(results);
        statusMap = statusMap == null ? Map.of() : new LinkedHashMap<>(statusMap);
        nodeElapsedMs = nodeElapsedMs == null ? Map.of() : new LinkedHashMap<>(nodeElapsedMs);
        mockedNodeIds = mockedNodeIds == null ? List.of() : List.copyOf(mockedNodeIds);
        realNodeIds = realNodeIds == null ? List.of() : List.copyOf(realNodeIds);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
        nodeFidelity = nodeFidelity == null ? Map.of() : new LinkedHashMap<>(nodeFidelity);
    }

    /** Backward-compatible constructor without node fidelity evidence. */
    public VisualGraphSimulationResponse(boolean validated, boolean compiled, boolean success,
            String graphName, String outputNode, Object output, Map<String, Object> results,
            Map<String, String> statusMap, long elapsedMs, Map<String, Long> nodeElapsedMs,
            List<String> mockedNodeIds, List<String> realNodeIds, boolean terminalOutputConforms,
            List<VisualDiagnostic> diagnostics, List<String> errors, String generatedDsl) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                nodeElapsedMs, mockedNodeIds, realNodeIds, terminalOutputConforms, diagnostics, errors,
                generatedDsl, Map.of());
    }
}
