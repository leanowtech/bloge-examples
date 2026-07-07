package com.leanowtech.bloge.gateway.visual.importer;

import java.util.List;

/**
 * Response for a batch DSL import commit.
 *
 * @param schemaVersion response schema version
 * @param mode import mode label
 * @param commitPolicy policy used to decide which projections are stored
 * @param summary aggregate commit and projection summary
 * @param items per-source commit results
 */
public record DslImportBatchCommitResult(
        String schemaVersion,
        String mode,
        String commitPolicy,
        DslImportBatchCommitSummary summary,
        List<DslImportBatchCommitItem> items
) {
    public static final String SCHEMA_VERSION = "bloge.dslImportBatchCommitResult.v1";

    /**
     * Creates a normalized response.
     */
    public DslImportBatchCommitResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        mode = mode == null || mode.isBlank() ? "batch-commit" : mode.trim();
        commitPolicy = commitPolicy == null || commitPolicy.isBlank() ? "renderable" : commitPolicy.trim();
        summary = summary == null
                ? new DslImportBatchCommitSummary(0, 0, 0, 0, null, null)
                : summary;
        items = items == null ? List.of() : List.copyOf(items);
    }
}
