package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

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
 * @param nodeElapsedMs per-node execution time in milliseconds
 * @param diagnostics validation and compilation diagnostics
 * @param errors execution or blocking errors
 * @param layout generated layout
 * @param decisionTable extracted decision table view
 * @param generatedDsl generated BLOGE DSL
 * @param validation draft or frozen publication validation/readiness used before execution
 * @param runId persisted run history id when the run was recorded
 * @param nodeAttempts exact operator invocation attempts keyed by node id
 * @param nodeExecutionFacts structured engine-observed execution semantics keyed by node id
 * @param runControl controlled-run lifecycle and termination proof
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
        Map<String, Long> nodeElapsedMs,
        List<VisualDiagnostic> diagnostics,
        List<String> errors,
        VisualRunLayout layout,
        VisualDecisionTable decisionTable,
        String generatedDsl,
        VisualValidationResult validation,
        String runId,
        Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
        Map<String, VisualNodeExecutionFact> nodeExecutionFacts,
        VisualRunControlView runControl
) {
    /**
     * Creates a response.
     */
    public VisualGraphRunResponse {
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        results = results == null ? Map.of() : new LinkedHashMap<>(results);
        statusMap = statusMap == null ? Map.of() : new LinkedHashMap<>(statusMap);
        nodeElapsedMs = nodeElapsedMs == null ? Map.of() : new LinkedHashMap<>(nodeElapsedMs);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
        runId = runId == null ? "" : runId;
        nodeAttempts = immutableAttempts(nodeAttempts);
        nodeExecutionFacts = nodeExecutionFacts == null ? Map.of() : new LinkedHashMap<>(nodeExecutionFacts);
        runControl = runControl == null ? VisualRunControlView.unmanaged() : runControl;
    }

    /** Backward-compatible constructor for unmanaged executions. */
    public VisualGraphRunResponse(boolean validated,
                                  boolean compiled,
                                  boolean success,
                                  String graphName,
                                  String outputNode,
                                  Object output,
                                  Map<String, Object> results,
                                  Map<String, String> statusMap,
                                  long elapsedMs,
                                  Map<String, Long> nodeElapsedMs,
                                  List<VisualDiagnostic> diagnostics,
                                  List<String> errors,
                                  VisualRunLayout layout,
                                  VisualDecisionTable decisionTable,
                                  String generatedDsl,
                                  VisualValidationResult validation,
                                  String runId,
                                  Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
                                  Map<String, VisualNodeExecutionFact> nodeExecutionFacts) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                nodeElapsedMs, diagnostics, errors, layout, decisionTable, generatedDsl, validation, runId,
                nodeAttempts, nodeExecutionFacts, VisualRunControlView.unmanaged());
    }

    /** Backward-compatible constructor for callers using the pre-semantics response shape. */
    public VisualGraphRunResponse(boolean validated,
                                  boolean compiled,
                                  boolean success,
                                  String graphName,
                                  String outputNode,
                                  Object output,
                                  Map<String, Object> results,
                                  Map<String, String> statusMap,
                                  long elapsedMs,
                                  Map<String, Long> nodeElapsedMs,
                                  List<VisualDiagnostic> diagnostics,
                                  List<String> errors,
                                  VisualRunLayout layout,
                                  VisualDecisionTable decisionTable,
                                  String generatedDsl,
                                  VisualValidationResult validation,
                                  String runId,
                                  Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                nodeElapsedMs, diagnostics, errors, layout, decisionTable, generatedDsl, validation, runId,
                nodeAttempts, Map.of(), VisualRunControlView.unmanaged());
    }

    /** Backward-compatible constructor for callers using the pre-capture response shape. */
    public VisualGraphRunResponse(boolean validated,
                                  boolean compiled,
                                  boolean success,
                                  String graphName,
                                  String outputNode,
                                  Object output,
                                  Map<String, Object> results,
                                  Map<String, String> statusMap,
                                  long elapsedMs,
                                  Map<String, Long> nodeElapsedMs,
                                  List<VisualDiagnostic> diagnostics,
                                  List<String> errors,
                                  VisualRunLayout layout,
                                  VisualDecisionTable decisionTable,
                                  String generatedDsl,
                                  VisualValidationResult validation,
                                  String runId) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                nodeElapsedMs, diagnostics, errors, layout, decisionTable, generatedDsl, validation, runId,
                Map.of(), Map.of());
    }

    /**
     * Backward-compatible constructor for callers that do not yet know a run history id.
     */
    public VisualGraphRunResponse(boolean validated,
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
                                  VisualRunLayout layout,
                                  VisualDecisionTable decisionTable,
                                  String generatedDsl) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                Map.of(), diagnostics, errors, layout, decisionTable, generatedDsl,
                new VisualValidationResult(false, diagnostics), "", Map.of(), Map.of());
    }

    /**
     * Backward-compatible constructor for callers that know node timings but do not yet know a run history id.
     */
    public VisualGraphRunResponse(boolean validated,
                                  boolean compiled,
                                  boolean success,
                                  String graphName,
                                  String outputNode,
                                  Object output,
                                  Map<String, Object> results,
                                  Map<String, String> statusMap,
                                  long elapsedMs,
                                  Map<String, Long> nodeElapsedMs,
                                  List<VisualDiagnostic> diagnostics,
                                  List<String> errors,
                                  VisualRunLayout layout,
                                  VisualDecisionTable decisionTable,
                                  String generatedDsl) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                nodeElapsedMs, diagnostics, errors, layout, decisionTable, generatedDsl,
                new VisualValidationResult(false, diagnostics), "", Map.of(), Map.of());
    }

    /**
     * Backward-compatible constructor for callers that know validation but do not yet know a run history id.
     */
    public VisualGraphRunResponse(boolean validated,
                                  boolean compiled,
                                  boolean success,
                                  String graphName,
                                  String outputNode,
                                  Object output,
                                  Map<String, Object> results,
                                  Map<String, String> statusMap,
                                  long elapsedMs,
                                  Map<String, Long> nodeElapsedMs,
                                  List<VisualDiagnostic> diagnostics,
                                  List<String> errors,
                                  VisualRunLayout layout,
                                  VisualDecisionTable decisionTable,
                                  String generatedDsl,
                                  VisualValidationResult validation) {
        this(validated, compiled, success, graphName, outputNode, output, results, statusMap, elapsedMs,
                nodeElapsedMs, diagnostics, errors, layout, decisionTable, generatedDsl, validation, "", Map.of());
    }

    /**
     * @param newRunId persisted run history id
     * @return copy with run id populated
     */
    public VisualGraphRunResponse withRunId(String newRunId) {
        return new VisualGraphRunResponse(validated, compiled, success, graphName, outputNode, output,
                results, statusMap, elapsedMs, nodeElapsedMs, diagnostics, errors, layout, decisionTable,
                generatedDsl, validation, newRunId, nodeAttempts, nodeExecutionFacts, runControl);
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
}
