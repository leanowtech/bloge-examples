package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shape-only replay view for one visual graph run.
 *
 * @param schemaVersion trace schema version
 * @param runId run id
 * @param sourceKind run source kind
 * @param graphName graph name
 * @param outputNode selected output node
 * @param createdAt run creation timestamp
 * @param success whether execution succeeded
 * @param elapsedMs total run elapsed milliseconds
 * @param contextSummary submitted context shape summary
 * @param outputSummary selected output shape summary
 * @param nodes node-level status and result-shape summaries
 * @param diagnostics run diagnostics
 * @param errors run errors
 * @param generatedDsl generated or frozen DSL used for execution
 */
public record VisualGraphRunTrace(
        String schemaVersion,
        String runId,
        String sourceKind,
        String graphName,
        String outputNode,
        Instant createdAt,
        boolean success,
        long elapsedMs,
        Map<String, Object> contextSummary,
        Map<String, Object> outputSummary,
        List<NodeTrace> nodes,
        List<VisualDiagnostic> diagnostics,
        List<String> errors,
        String generatedDsl
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphRunTrace.v1";

    /**
     * Creates a trace.
     */
    public VisualGraphRunTrace {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        runId = runId == null ? "" : runId;
        sourceKind = sourceKind == null ? "" : sourceKind;
        graphName = graphName == null ? "" : graphName;
        outputNode = outputNode == null ? "" : outputNode;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        elapsedMs = Math.max(0, elapsedMs);
        contextSummary = contextSummary == null ? Map.of() : new LinkedHashMap<>(contextSummary);
        outputSummary = outputSummary == null ? Map.of() : new LinkedHashMap<>(outputSummary);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
    }

    /**
     * Builds a trace from a persisted run record.
     *
     * @param record persisted run record
     * @return shape-only run trace
     */
    public static VisualGraphRunTrace from(VisualGraphRunRecord record) {
        if (record == null) {
            return new VisualGraphRunTrace("", "", "", "", "", null, false, 0,
                    Map.of(), Map.of(), List.of(), List.of(), List.of(), "");
        }
        return new VisualGraphRunTrace(
                "",
                record.runId(),
                record.sourceKind(),
                record.graphName(),
                record.outputNode(),
                record.createdAt(),
                record.success(),
                record.elapsedMs(),
                record.contextSummary(),
                record.outputSummary(),
                nodeTraces(record),
                record.diagnostics(),
                record.errors(),
                record.generatedDsl()
        );
    }

    private static List<NodeTrace> nodeTraces(VisualGraphRunRecord record) {
        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.addAll(record.statusMap().keySet());
        nodeIds.addAll(record.resultsSummary().keySet());
        if (!record.outputNode().isBlank()) {
            nodeIds.add(record.outputNode());
        }
        return nodeIds.stream()
                .map(nodeId -> new NodeTrace(
                        nodeId,
                        record.statusMap().getOrDefault(nodeId, ""),
                        nodeId.equals(record.outputNode()),
                        resultSummaryFor(record.resultsSummary(), nodeId),
                        record.statusMap().containsKey(nodeId),
                        record.resultsSummary().containsKey(nodeId)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultSummaryFor(Map<String, Object> resultsSummary, String nodeId) {
        Object raw = resultsSummary.get(nodeId);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> summary.put(String.valueOf(key), value));
        return summary;
    }

    /**
     * Node-level replay row.
     *
     * @param nodeId node id
     * @param status node execution status
     * @param outputSelected whether this node supplied selected graph output
     * @param resultSummary shape-only result summary
     * @param statusKnown whether the run recorded a status for this node
     * @param resultKnown whether the run recorded a result summary for this node
     */
    public record NodeTrace(
            String nodeId,
            String status,
            boolean outputSelected,
            Map<String, Object> resultSummary,
            boolean statusKnown,
            boolean resultKnown
    ) {
        /**
         * Creates a node trace row.
         */
        public NodeTrace {
            nodeId = nodeId == null ? "" : nodeId;
            status = status == null ? "" : status;
            resultSummary = resultSummary == null ? Map.of() : new LinkedHashMap<>(resultSummary);
        }
    }
}
