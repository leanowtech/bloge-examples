package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable review of an AsyncAPI operator-library projection preview.
 *
 * @param schemaVersion review schema version
 * @param availableOperationCount number of discovered operation/message candidates
 * @param selectedOperationCount number of candidates selected for projection
 * @param omittedOperationCount number of discovered candidates omitted from projection
 * @param selectionApplied true when the request used a selector
 * @param coverageStatus FULL, PARTIAL, or NO_MATCH
 * @param unmatchedSelectionCount selector count that matched no discovered candidate
 * @param selectionMatches per-selector match evidence
 * @param omittedOperations candidates not included in the projected library
 * @param availableProjectionLevelCounts projection-level counts across discovered candidates
 * @param selectedProjectionLevelCounts projection-level counts across selected candidates
 * @param selectedSourceKindCounts source-kind counts across selected candidates
 */
public record AsyncApiProjectionReview(
        String schemaVersion,
        int availableOperationCount,
        int selectedOperationCount,
        int omittedOperationCount,
        boolean selectionApplied,
        String coverageStatus,
        int unmatchedSelectionCount,
        List<AsyncApiSelectionMatch> selectionMatches,
        List<AsyncApiOmittedOperation> omittedOperations,
        Map<String, Integer> availableProjectionLevelCounts,
        Map<String, Integer> selectedProjectionLevelCounts,
        Map<String, Integer> selectedSourceKindCounts
) {
    public static final String SCHEMA_VERSION = "bloge.asyncApiProjectionReview.v1";

    /**
     * Creates a normalized review.
     */
    public AsyncApiProjectionReview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        availableOperationCount = Math.max(0, availableOperationCount);
        selectedOperationCount = Math.max(0, selectedOperationCount);
        omittedOperationCount = Math.max(0, omittedOperationCount);
        coverageStatus = coverageStatus == null || coverageStatus.isBlank()
                ? "FULL"
                : coverageStatus.toUpperCase();
        unmatchedSelectionCount = Math.max(0, unmatchedSelectionCount);
        selectionMatches = selectionMatches == null ? List.of() : List.copyOf(selectionMatches);
        omittedOperations = omittedOperations == null ? List.of() : List.copyOf(omittedOperations);
        availableProjectionLevelCounts = availableProjectionLevelCounts == null
                ? Map.of()
                : Map.copyOf(availableProjectionLevelCounts);
        selectedProjectionLevelCounts = selectedProjectionLevelCounts == null
                ? Map.of()
                : Map.copyOf(selectedProjectionLevelCounts);
        selectedSourceKindCounts = selectedSourceKindCounts == null ? Map.of() : Map.copyOf(selectedSourceKindCounts);
    }

    /**
     * Empty review.
     */
    public static AsyncApiProjectionReview empty() {
        return new AsyncApiProjectionReview(SCHEMA_VERSION, 0, 0, 0, false, "FULL",
                0, List.of(), List.of(), Map.of(), Map.of(), Map.of());
    }
}
