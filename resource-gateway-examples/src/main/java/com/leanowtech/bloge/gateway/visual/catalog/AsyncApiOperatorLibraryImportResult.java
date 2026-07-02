package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Preview result for projecting AsyncAPI into a visual operator-library draft.
 *
 * @param schemaVersion result schema version
 * @param library generated operator-library draft; never stored by the preview endpoint
 * @param validation structural validation, registry impact, and profile evidence for the generated library
 * @param availableOperations all discovered operation/message projection candidates in the source AsyncAPI document
 * @param selectedOperations operation/message candidates used to generate this preview library
 * @param omittedOperationCount number of discovered candidates intentionally omitted by selector-based projection
 * @param selectionApplied true when the request used a single or batch operation/message selector
 * @param projectionReview machine-readable coverage, selector-match, and omission evidence
 */
public record AsyncApiOperatorLibraryImportResult(
        String schemaVersion,
        OperatorLibrary library,
        OperatorLibraryValidationResult validation,
        List<AsyncApiOperationSummary> availableOperations,
        List<AsyncApiOperationSummary> selectedOperations,
        int omittedOperationCount,
        boolean selectionApplied,
        AsyncApiProjectionReview projectionReview
) {
    public static final String SCHEMA_VERSION = "bloge.asyncApiOperatorLibraryImportResult.v1";

    /**
     * Creates a result.
     */
    public AsyncApiOperatorLibraryImportResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        validation = validation == null
                ? new OperatorLibraryValidationResult(false, java.util.List.of(),
                OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty())
                : validation;
        availableOperations = availableOperations == null ? List.of() : List.copyOf(availableOperations);
        selectedOperations = selectedOperations == null ? List.of() : List.copyOf(selectedOperations);
        omittedOperationCount = Math.max(0, omittedOperationCount);
        projectionReview = projectionReview == null ? AsyncApiProjectionReview.empty() : projectionReview;
    }

    /**
     * Creates a result using the current schema version.
     */
    public AsyncApiOperatorLibraryImportResult(OperatorLibrary library,
                                               OperatorLibraryValidationResult validation) {
        this(SCHEMA_VERSION, library, validation, List.of(), List.of(), 0, false,
                AsyncApiProjectionReview.empty());
    }

    /**
     * Creates a result with projection audit evidence using the current schema version.
     */
    public AsyncApiOperatorLibraryImportResult(OperatorLibrary library,
                                               OperatorLibraryValidationResult validation,
                                               List<AsyncApiOperationSummary> availableOperations,
                                               List<AsyncApiOperationSummary> selectedOperations,
                                               int omittedOperationCount,
                                               boolean selectionApplied) {
        this(SCHEMA_VERSION, library, validation, availableOperations, selectedOperations,
                omittedOperationCount, selectionApplied, null);
    }

    /**
     * Creates a result with projection review using the current schema version.
     */
    public AsyncApiOperatorLibraryImportResult(OperatorLibrary library,
                                               OperatorLibraryValidationResult validation,
                                               List<AsyncApiOperationSummary> availableOperations,
                                               List<AsyncApiOperationSummary> selectedOperations,
                                               int omittedOperationCount,
                                               boolean selectionApplied,
                                               AsyncApiProjectionReview projectionReview) {
        this(SCHEMA_VERSION, library, validation, availableOperations, selectedOperations,
                omittedOperationCount, selectionApplied, projectionReview);
    }
}
