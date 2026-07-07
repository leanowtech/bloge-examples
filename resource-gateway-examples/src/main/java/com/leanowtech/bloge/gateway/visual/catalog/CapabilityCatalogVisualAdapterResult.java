package com.leanowtech.bloge.gateway.visual.catalog;

/**
 * Response returned by the capability-catalog preview adapter.
 *
 * @param schemaVersion result schema version
 * @param library generated visual operator-library draft; null when projection cannot start
 * @param validation adapter and visual-library validation evidence
 * @param projectionReview projection coverage summary
 */
public record CapabilityCatalogVisualAdapterResult(
        String schemaVersion,
        OperatorLibrary library,
        OperatorLibraryValidationResult validation,
        CapabilityCatalogProjectionReview projectionReview
) {
    public static final String SCHEMA_VERSION = "bloge.capabilityCatalogVisualAdapterResult.v1";

    /**
     * Creates a normalized adapter result.
     */
    public CapabilityCatalogVisualAdapterResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        validation = validation == null
                ? new OperatorLibraryValidationResult(false, java.util.List.of(),
                OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty())
                : validation;
        projectionReview = projectionReview == null
                ? CapabilityCatalogProjectionReview.empty("", "")
                : projectionReview;
    }
}
