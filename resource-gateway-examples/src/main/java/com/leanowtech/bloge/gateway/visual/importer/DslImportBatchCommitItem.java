package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraftImportResult;

/**
 * Per-source result for a DSL batch commit attempt.
 *
 * @param sourceId source DSL id
 * @param graphName projected graph name when available
 * @param committed whether a governed draft revision was stored
 * @param commitDecision machine-readable commit decision
 * @param message human-readable commit decision message
 * @param reportItem projection/report evidence for this source
 * @param importResult stored, skipped, or rejected draft import evidence
 */
public record DslImportBatchCommitItem(
        String sourceId,
        String graphName,
        boolean committed,
        String commitDecision,
        String message,
        DslImportBatchReportItem reportItem,
        GraphDraftImportResult importResult
) {
    /**
     * Creates a normalized commit item.
     */
    public DslImportBatchCommitItem {
        sourceId = sourceId == null ? "" : sourceId;
        graphName = graphName == null ? "" : graphName;
        commitDecision = commitDecision == null || commitDecision.isBlank()
                ? "SKIP_NOT_ASSESSED"
                : commitDecision;
        message = message == null ? "" : message;
    }
}
