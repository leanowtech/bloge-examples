package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * Response for stored contract-test suite catalog.
 *
 * @param schemaVersion response schema version
 * @param suites suite summaries
 */
public record GatewayGraphContractTestSuiteCatalogResponse(
        String schemaVersion,
        List<GatewayGraphContractTestSuiteSummary> suites
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestSuiteCatalog.v1";

    /**
     * Creates a catalog response.
     */
    public GatewayGraphContractTestSuiteCatalogResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        suites = suites == null ? List.of() : List.copyOf(suites);
    }

    /**
     * Convenience constructor.
     */
    public GatewayGraphContractTestSuiteCatalogResponse(List<GatewayGraphContractTestSuiteSummary> suites) {
        this(SCHEMA_VERSION, suites);
    }
}
