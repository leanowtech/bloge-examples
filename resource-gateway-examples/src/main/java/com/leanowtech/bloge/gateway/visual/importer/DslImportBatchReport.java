package com.leanowtech.bloge.gateway.visual.importer;

import java.util.List;

/**
 * Batch migration readiness report for existing BLOGE DSL sources.
 *
 * @param schemaVersion response schema version
 * @param mode import mode label
 * @param summary aggregate readiness and coverage summary
 * @param items per-source report items
 */
public record DslImportBatchReport(
        String schemaVersion,
        String mode,
        DslImportBatchSummary summary,
        List<DslImportBatchReportItem> items
) {
    public static final String SCHEMA_VERSION = "bloge.dslImportBatchReport.v1";

    /**
     * Creates a normalized report.
     */
    public DslImportBatchReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        mode = mode == null || mode.isBlank() ? "batch-report" : mode;
        summary = summary == null
                ? new DslImportBatchSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of())
                : summary;
        items = items == null ? List.of() : List.copyOf(items);
    }
}
