package com.leanowtech.bloge.gateway.visual.importer;

import java.util.Map;

/**
 * Aggregate migration readiness summary for a DSL batch import report.
 *
 * @param sourceCount total source count
 * @param renderableSourceCount sources that parsed into graph projections
 * @param fullyProjectedSourceCount sources with no import errors or repair markers
 * @param repairableSourceCount sources that rendered but require repair
 * @param blockedSourceCount sources blocked before graph projection
 * @param rewriteAllowedSourceCount sources whose generated DSL may enter source replacement flow
 * @param rewriteBlockedSourceCount sources whose generated DSL is not safe for source replacement
 * @param totalMemberCount total parsed graph members
 * @param totalProjectedNodeCount total projected visual nodes
 * @param totalEdgeCount total projected visual edges
 * @param totalUnsupportedSyntaxCount total unsupported syntax markers
 * @param totalMissingOperatorCount total missing operator references
 * @param totalMissingFunctionCount total missing function references
 * @param totalSourceMapEntryCount total source-map entries
 * @param roundTripStatusCounts counts by round-trip status
 * @param rewriteDecisionCounts counts by rewrite gate decision
 * @param diagnosticLevelCounts counts by diagnostic level
 */
public record DslImportBatchSummary(
        int sourceCount,
        int renderableSourceCount,
        int fullyProjectedSourceCount,
        int repairableSourceCount,
        int blockedSourceCount,
        int rewriteAllowedSourceCount,
        int rewriteBlockedSourceCount,
        int totalMemberCount,
        int totalProjectedNodeCount,
        int totalEdgeCount,
        int totalUnsupportedSyntaxCount,
        int totalMissingOperatorCount,
        int totalMissingFunctionCount,
        int totalSourceMapEntryCount,
        Map<String, Integer> roundTripStatusCounts,
        Map<String, Integer> rewriteDecisionCounts,
        Map<String, Integer> diagnosticLevelCounts
) {
    /**
     * Creates a normalized summary.
     */
    public DslImportBatchSummary {
        roundTripStatusCounts = roundTripStatusCounts == null ? Map.of() : Map.copyOf(roundTripStatusCounts);
        rewriteDecisionCounts = rewriteDecisionCounts == null ? Map.of() : Map.copyOf(rewriteDecisionCounts);
        diagnosticLevelCounts = diagnosticLevelCounts == null ? Map.of() : Map.copyOf(diagnosticLevelCounts);
    }
}
