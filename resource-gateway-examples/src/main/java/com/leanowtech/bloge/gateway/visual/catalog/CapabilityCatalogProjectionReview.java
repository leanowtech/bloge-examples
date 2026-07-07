package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Projection review for adapting BLOGE framework capability catalogs into the
 * visual operator-library contract consumed by the generic canvas.
 *
 * @param schemaVersion review schema version
 * @param catalogId source catalog id
 * @param sourceSchemaVersion source catalog schema version
 * @param sourceOperatorCount operator count in the source catalog
 * @param sourceFunctionCount built-in function count in the source catalog
 * @param projectedOperatorCount operator count projected into the visual library draft
 * @param projectedFunctionCount built-in function count projected into the visual library draft
 * @param opaqueSchemaCount number of schema surfaces that had to fall back to an opaque object schema
 * @param sourceDiagnosticCount diagnostics carried by the source catalog
 * @param coverageStatus compact projection coverage status
 * @param sourceKinds implementation kinds observed in the source catalog
 */
public record CapabilityCatalogProjectionReview(
        String schemaVersion,
        String catalogId,
        String sourceSchemaVersion,
        int sourceOperatorCount,
        int sourceFunctionCount,
        int projectedOperatorCount,
        int projectedFunctionCount,
        int opaqueSchemaCount,
        int sourceDiagnosticCount,
        String coverageStatus,
        List<String> sourceKinds
) {
    public static final String SCHEMA_VERSION = "bloge.capabilityCatalogProjectionReview.v1";

    /**
     * Creates a normalized projection review.
     */
    public CapabilityCatalogProjectionReview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        catalogId = catalogId == null ? "" : catalogId;
        sourceSchemaVersion = sourceSchemaVersion == null ? "" : sourceSchemaVersion;
        sourceOperatorCount = Math.max(0, sourceOperatorCount);
        sourceFunctionCount = Math.max(0, sourceFunctionCount);
        projectedOperatorCount = Math.max(0, projectedOperatorCount);
        projectedFunctionCount = Math.max(0, projectedFunctionCount);
        opaqueSchemaCount = Math.max(0, opaqueSchemaCount);
        sourceDiagnosticCount = Math.max(0, sourceDiagnosticCount);
        coverageStatus = coverageStatus == null || coverageStatus.isBlank() ? "NO_MATCH" : coverageStatus;
        sourceKinds = sourceKinds == null ? List.of() : sourceKinds.stream()
                .filter(kind -> kind != null && !kind.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * @param sourceSchemaVersion source schema version when known
     * @param catalogId source catalog id when known
     * @return empty review used when parsing or schema checks fail before projection
     */
    public static CapabilityCatalogProjectionReview empty(String sourceSchemaVersion, String catalogId) {
        return new CapabilityCatalogProjectionReview(
                SCHEMA_VERSION,
                catalogId,
                sourceSchemaVersion,
                0,
                0,
                0,
                0,
                0,
                0,
                "NO_MATCH",
                List.of()
        );
    }
}
