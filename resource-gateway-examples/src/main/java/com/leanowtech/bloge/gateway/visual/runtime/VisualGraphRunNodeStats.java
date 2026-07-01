package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node-level aggregate health view for a filtered visual graph run-history window.
 *
 * <p>The elapsed values are observed whole-run latencies for runs where the node
 * appeared in the persisted trace material. They are not per-operator execution
 * timings until the runtime starts recording node-level duration events.
 *
 * @param schemaVersion node stats schema version
 * @param totalRuns total matching run records used for the aggregation
 * @param nodes node aggregate rows
 */
public record VisualGraphRunNodeStats(
        String schemaVersion,
        int totalRuns,
        List<NodeStats> nodes
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphRunNodeStats.v1";

    /**
     * Creates node stats.
     */
    public VisualGraphRunNodeStats {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        totalRuns = Math.max(0, totalRuns);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * Builds node stats from newest-first or arbitrary run records.
     *
     * @param records run records
     * @return node aggregate stats
     */
    public static VisualGraphRunNodeStats from(Collection<VisualGraphRunRecord> records) {
        List<VisualGraphRunRecord> runs = records == null ? List.of() : records.stream().toList();
        Map<String, NodeAccumulator> accumulators = new LinkedHashMap<>();
        for (VisualGraphRunRecord run : runs) {
            Set<String> nodeIds = nodeIds(run);
            for (String nodeId : nodeIds) {
                if (nodeId.isBlank()) {
                    continue;
                }
                VisualGraphRunRecord.NodeSnapshot snapshot = run.nodeSnapshots().get(nodeId);
                List<VisualDiagnostic> diagnostics = diagnosticsForNode(run.diagnostics(), nodeId, snapshot);
                accumulators.computeIfAbsent(nodeId, NodeAccumulator::new)
                        .add(run, nodeId, snapshot, diagnostics);
            }
        }
        List<NodeStats> nodes = accumulators.values().stream()
                .map(NodeAccumulator::toStats)
                .sorted(Comparator
                        .comparingInt((NodeStats node) -> node.nodeIndex() < 0
                                ? Integer.MAX_VALUE
                                : node.nodeIndex())
                        .thenComparing(NodeStats::nodeId))
                .toList();
        return new VisualGraphRunNodeStats("", runs.size(), nodes);
    }

    private static Set<String> nodeIds(VisualGraphRunRecord record) {
        if (record == null) {
            return Set.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.addAll(record.statusMap().keySet());
        nodeIds.addAll(record.resultsSummary().keySet());
        nodeIds.addAll(record.nodeSnapshots().keySet());
        if (!record.outputNode().isBlank()) {
            nodeIds.add(record.outputNode());
        }
        return nodeIds;
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

    private static Instant earliest(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key == null || key.isBlank() ? "UNKNOWN" : key, 1, Integer::sum);
    }

    private static final class NodeAccumulator {
        private final String nodeId;
        private int nodeIndex = -1;
        private String operatorRef = "";
        private String label = "";
        private Instant metadataSeenAt;
        private int runCount;
        private int successfulRuns;
        private int failedRuns;
        private int statusKnownRuns;
        private int resultKnownRuns;
        private int outputSelectedRuns;
        private int diagnosticCount;
        private int errorCount;
        private long maxObservedElapsedMs;
        private Instant firstSeenAt;
        private Instant latestSeenAt;
        private final List<Long> observedElapsedMs = new ArrayList<>();
        private final Map<String, Integer> statusCounts = new LinkedHashMap<>();

        private NodeAccumulator(String nodeId) {
            this.nodeId = nodeId;
        }

        private void add(VisualGraphRunRecord run,
                         String currentNodeId,
                         VisualGraphRunRecord.NodeSnapshot snapshot,
                         List<VisualDiagnostic> diagnostics) {
            runCount++;
            if (run.success()) {
                successfulRuns++;
            } else {
                failedRuns++;
            }
            if (run.statusMap().containsKey(currentNodeId)) {
                statusKnownRuns++;
                increment(statusCounts, run.statusMap().get(currentNodeId));
            }
            if (run.resultsSummary().containsKey(currentNodeId)) {
                resultKnownRuns++;
            }
            if (currentNodeId.equals(run.outputNode())) {
                outputSelectedRuns++;
            }
            diagnosticCount += diagnostics.size();
            errorCount += (int) diagnostics.stream().filter(VisualDiagnostic::error).count();
            observedElapsedMs.add(run.elapsedMs());
            maxObservedElapsedMs = Math.max(maxObservedElapsedMs, run.elapsedMs());
            firstSeenAt = earliest(firstSeenAt, run.createdAt());
            latestSeenAt = latest(latestSeenAt, run.createdAt());
            captureLatestMetadata(snapshot, run.createdAt());
        }

        private void captureLatestMetadata(VisualGraphRunRecord.NodeSnapshot snapshot, Instant seenAt) {
            if (snapshot == null) {
                return;
            }
            if (metadataSeenAt != null && seenAt != null && seenAt.isBefore(metadataSeenAt)) {
                return;
            }
            nodeIndex = snapshot.nodeIndex();
            operatorRef = snapshot.operatorRef();
            label = snapshot.label();
            metadataSeenAt = seenAt;
        }

        private NodeStats toStats() {
            return new NodeStats(
                    nodeId,
                    nodeIndex,
                    operatorRef,
                    label,
                    runCount,
                    successfulRuns,
                    failedRuns,
                    statusKnownRuns,
                    resultKnownRuns,
                    outputSelectedRuns,
                    diagnosticCount,
                    errorCount,
                    percentile(observedElapsedMs, 0.50D),
                    percentile(observedElapsedMs, 0.95D),
                    maxObservedElapsedMs,
                    firstSeenAt,
                    latestSeenAt,
                    statusCounts
            );
        }
    }

    /**
     * Node aggregate row.
     *
     * @param nodeId node id
     * @param nodeIndex latest known zero-based draft node index, or -1 when unavailable
     * @param operatorRef latest known operator reference
     * @param label latest known display label
     * @param runCount number of matching records where this node appeared
     * @param successfulRuns matching node records from successful runs
     * @param failedRuns matching node records from failed or blocked runs
     * @param statusKnownRuns records where the node had an execution status
     * @param resultKnownRuns records where the node had a result summary
     * @param outputSelectedRuns records where the node supplied selected output
     * @param diagnosticCount diagnostics attributed to this node
     * @param errorCount error diagnostics attributed to this node
     * @param p50ObservedElapsedMs nearest-rank p50 whole-run latency for runs containing the node
     * @param p95ObservedElapsedMs nearest-rank p95 whole-run latency for runs containing the node
     * @param maxObservedElapsedMs maximum whole-run latency for runs containing the node
     * @param firstSeenAt oldest matching record timestamp for this node
     * @param latestSeenAt newest matching record timestamp for this node
     * @param statusCounts node execution status counts
     */
    public record NodeStats(
            String nodeId,
            int nodeIndex,
            String operatorRef,
            String label,
            int runCount,
            int successfulRuns,
            int failedRuns,
            int statusKnownRuns,
            int resultKnownRuns,
            int outputSelectedRuns,
            int diagnosticCount,
            int errorCount,
            long p50ObservedElapsedMs,
            long p95ObservedElapsedMs,
            long maxObservedElapsedMs,
            Instant firstSeenAt,
            Instant latestSeenAt,
            Map<String, Integer> statusCounts
    ) {
        /**
         * Creates a node aggregate row.
         */
        public NodeStats {
            nodeId = nodeId == null ? "" : nodeId;
            nodeIndex = Math.max(-1, nodeIndex);
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null ? "" : label;
            runCount = Math.max(0, runCount);
            successfulRuns = Math.max(0, successfulRuns);
            failedRuns = Math.max(0, failedRuns);
            statusKnownRuns = Math.max(0, statusKnownRuns);
            resultKnownRuns = Math.max(0, resultKnownRuns);
            outputSelectedRuns = Math.max(0, outputSelectedRuns);
            diagnosticCount = Math.max(0, diagnosticCount);
            errorCount = Math.max(0, errorCount);
            p50ObservedElapsedMs = Math.max(0, p50ObservedElapsedMs);
            p95ObservedElapsedMs = Math.max(0, p95ObservedElapsedMs);
            maxObservedElapsedMs = Math.max(0, maxObservedElapsedMs);
            statusCounts = statusCounts == null ? Map.of() : new LinkedHashMap<>(statusCounts);
        }
    }
}
