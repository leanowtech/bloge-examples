package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;
import java.util.Map;

/**
 * Per-source result in a DSL batch migration report.
 *
 * @param sourceId source DSL id
 * @param graphName projected graph name
 * @param renderable whether the source was parsed into a graph-level visual projection
 * @param fullyProjected whether the source has no import errors or loss-aware repair markers
 * @param needsRepair whether the source rendered but still needs catalog/schema/syntax repair
 * @param sourceMapEntryCount number of source-map entries emitted for this source
 * @param coverage projection coverage summary
 * @param roundTrip round-trip evidence
 * @param rewriteAllowed whether generated DSL is eligible for source replacement
 * @param rewriteDecision rewrite gate decision
 * @param diagnosticLevelCounts counts by diagnostic level
 * @param diagnostics source diagnostics
 * @param draft optional projected draft when request.includeDrafts is true
 */
public record DslImportBatchReportItem(
        String sourceId,
        String graphName,
        boolean renderable,
        boolean fullyProjected,
        boolean needsRepair,
        int sourceMapEntryCount,
        DslImportCoverage coverage,
        DslRoundTripSummary roundTrip,
        boolean rewriteAllowed,
        String rewriteDecision,
        Map<String, Integer> diagnosticLevelCounts,
        List<VisualDiagnostic> diagnostics,
        GraphDraft draft
) {
    /**
     * Creates a normalized report item.
     */
    public DslImportBatchReportItem {
        sourceId = sourceId == null ? "" : sourceId;
        graphName = graphName == null ? "" : graphName;
        coverage = coverage == null ? DslImportCoverage.empty() : coverage;
        roundTrip = roundTrip == null ? DslRoundTripSummary.notAssessed() : roundTrip;
        rewriteDecision = rewriteDecision == null || rewriteDecision.isBlank()
                ? "BLOCK_NOT_ASSESSED"
                : rewriteDecision;
        diagnosticLevelCounts = diagnosticLevelCounts == null ? Map.of() : Map.copyOf(diagnosticLevelCounts);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
