package com.leanowtech.bloge.gateway.visual.catalog;

/**
 * Preview result for projecting AsyncAPI into a visual operator-library draft.
 *
 * @param schemaVersion result schema version
 * @param library generated operator-library draft; never stored by the preview endpoint
 * @param validation structural validation, registry impact, and profile evidence for the generated library
 */
public record AsyncApiOperatorLibraryImportResult(
        String schemaVersion,
        OperatorLibrary library,
        OperatorLibraryValidationResult validation
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
    }

    /**
     * Creates a result using the current schema version.
     */
    public AsyncApiOperatorLibraryImportResult(OperatorLibrary library,
                                               OperatorLibraryValidationResult validation) {
        this(SCHEMA_VERSION, library, validation);
    }
}
