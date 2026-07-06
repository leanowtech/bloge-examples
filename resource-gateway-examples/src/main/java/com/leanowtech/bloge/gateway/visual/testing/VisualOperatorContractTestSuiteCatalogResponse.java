package com.leanowtech.bloge.gateway.visual.testing;

import java.util.List;

/**
 * Stored operator contract-test suite catalog response.
 *
 * @param schemaVersion response schema version
 * @param suites stored suite summaries
 */
public record VisualOperatorContractTestSuiteCatalogResponse(
        String schemaVersion,
        List<VisualOperatorContractTestSuiteSummary> suites
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestSuites.v1";

    /**
     * Creates a catalog response.
     */
    public VisualOperatorContractTestSuiteCatalogResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        suites = suites == null ? List.of() : List.copyOf(suites);
    }

    /**
     * Convenience constructor.
     */
    public VisualOperatorContractTestSuiteCatalogResponse(List<VisualOperatorContractTestSuiteSummary> suites) {
        this(SCHEMA_VERSION, suites);
    }
}
