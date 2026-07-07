package com.leanowtech.bloge.gateway.visual.importer;

import java.util.Map;

/**
 * Aggregate result for a DSL batch commit attempt.
 *
 * @param sourceCount total requested source count
 * @param committedSourceCount sources persisted as governed graph drafts
 * @param skippedSourceCount sources intentionally skipped by parse/root/policy gates
 * @param failedSourceCount sources attempted but not persisted due to storage failures
 * @param reportSummary projection readiness summary for the same source window
 * @param commitDecisionCounts counts grouped by commit decision
 */
public record DslImportBatchCommitSummary(
        int sourceCount,
        int committedSourceCount,
        int skippedSourceCount,
        int failedSourceCount,
        DslImportBatchSummary reportSummary,
        Map<String, Integer> commitDecisionCounts
) {
    /**
     * Creates a normalized summary.
     */
    public DslImportBatchCommitSummary {
        sourceCount = Math.max(0, sourceCount);
        committedSourceCount = Math.max(0, committedSourceCount);
        skippedSourceCount = Math.max(0, skippedSourceCount);
        failedSourceCount = Math.max(0, failedSourceCount);
        reportSummary = reportSummary == null
                ? new DslImportBatchSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), Map.of(), Map.of())
                : reportSummary;
        commitDecisionCounts = commitDecisionCounts == null ? Map.of() : Map.copyOf(commitDecisionCounts);
    }
}
