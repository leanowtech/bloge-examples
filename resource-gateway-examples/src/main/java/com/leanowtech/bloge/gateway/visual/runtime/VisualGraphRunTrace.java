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
        nodeIds.addAll(record.nodeSnapshots().keySet());
        if (!record.outputNode().isBlank()) {
            nodeIds.add(record.outputNode());
        }
        return nodeIds.stream()
                .map(nodeId -> nodeTrace(record, nodeId))
                .toList();
    }

    private static NodeTrace nodeTrace(VisualGraphRunRecord record, String nodeId) {
        VisualGraphRunRecord.NodeSnapshot snapshot = record.nodeSnapshots().get(nodeId);
        List<VisualDiagnostic> diagnostics = diagnosticsForNode(record.diagnostics(), nodeId, snapshot);
        return new NodeTrace(
                nodeId,
                snapshot == null ? -1 : snapshot.nodeIndex(),
                snapshot == null ? "" : snapshot.operatorRef(),
                snapshot == null ? "" : snapshot.label(),
                record.statusMap().getOrDefault(nodeId, ""),
                record.nodeElapsedMs().getOrDefault(nodeId, 0L),
                record.nodeElapsedMs().containsKey(nodeId),
                nodeId.equals(record.outputNode()),
                resultSummaryFor(record.resultsSummary(), nodeId),
                record.statusMap().containsKey(nodeId),
                record.resultsSummary().containsKey(nodeId),
                diagnostics,
                diagnostics.size(),
                (int) diagnostics.stream().filter(VisualDiagnostic::error).count());
    }

    private static Map<String, Object> resultSummaryFor(Map<String, Object> resultsSummary, String nodeId) {
        Object raw = resultsSummary.get(nodeId);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> summary.put(String.valueOf(key), value));
        return summary;
    }

    private static List<VisualDiagnostic> diagnosticsForNode(List<VisualDiagnostic> diagnostics,
                                                             String nodeId,
                                                             VisualGraphRunRecord.NodeSnapshot snapshot) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        return diagnostics.stream()
                .filter(diagnostic -> targetsNode(diagnostic, nodeId, snapshot))
                .toList();
    }

    private static boolean targetsNode(VisualDiagnostic diagnostic,
                                       String nodeId,
                                       VisualGraphRunRecord.NodeSnapshot snapshot) {
        if (diagnostic == null || diagnostic.target().isBlank()) {
            return false;
        }
        String target = diagnostic.target();
        if (!nodeId.isBlank()
                && (matchesTargetPrefix(target, "/nodes/" + nodeId)
                || matchesTargetPrefix(target, "/draft/nodes/" + nodeId))) {
            return true;
        }
        if (snapshot == null || snapshot.nodeIndex() < 0) {
            return false;
        }
        String index = String.valueOf(snapshot.nodeIndex());
        return matchesTargetPrefix(target, "/nodes/" + index)
                || matchesTargetPrefix(target, "/draft/nodes/" + index);
    }

    private static boolean matchesTargetPrefix(String target, String prefix) {
        return target.equals(prefix) || target.startsWith(prefix + "/");
    }

    /**
     * Node-level replay row.
     *
     * @param nodeId node id
     * @param nodeIndex zero-based draft node index, or -1 when unavailable
     * @param operatorRef operator reference used by the node
     * @param label display label
     * @param status node execution status
     * @param elapsedMs node execution elapsed milliseconds
     * @param timingKnown whether the run recorded node execution timing
     * @param outputSelected whether this node supplied selected graph output
     * @param resultSummary shape-only result summary
     * @param statusKnown whether the run recorded a status for this node
     * @param resultKnown whether the run recorded a result summary for this node
     * @param diagnostics diagnostics targeting this node
     * @param diagnosticCount number of diagnostics targeting this node
     * @param errorCount number of error diagnostics targeting this node
     */
    public record NodeTrace(
            String nodeId,
            int nodeIndex,
            String operatorRef,
            String label,
            String status,
            long elapsedMs,
            boolean timingKnown,
            boolean outputSelected,
            Map<String, Object> resultSummary,
            boolean statusKnown,
            boolean resultKnown,
            List<VisualDiagnostic> diagnostics,
            int diagnosticCount,
            int errorCount
    ) {
        /**
         * Creates a node trace row.
         */
        public NodeTrace {
            nodeId = nodeId == null ? "" : nodeId;
            nodeIndex = Math.max(-1, nodeIndex);
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null ? "" : label;
            status = status == null ? "" : status;
            elapsedMs = Math.max(0, elapsedMs);
            resultSummary = resultSummary == null ? Map.of() : new LinkedHashMap<>(resultSummary);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            diagnosticCount = Math.max(0, diagnosticCount);
            errorCount = Math.max(0, errorCount);
        }
    }
}
