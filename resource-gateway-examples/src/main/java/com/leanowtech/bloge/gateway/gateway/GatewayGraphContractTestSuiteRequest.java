package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * Request to run a table-driven contract test suite for one resource graph.
 *
 * @param schemaVersion request schema version
 * @param graphName resource graph name
 * @param cases table rows to execute
 */
public record GatewayGraphContractTestSuiteRequest(
        String schemaVersion,
        String graphName,
        List<GatewayGraphContractTestCase> cases
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestSuiteRequest.v1";

    /**
     * Creates a suite request.
     */
    public GatewayGraphContractTestSuiteRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        graphName = graphName == null ? "" : graphName.trim();
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * Convenience constructor for callers that use the current schema version.
     */
    public GatewayGraphContractTestSuiteRequest(String graphName,
                                                List<GatewayGraphContractTestCase> cases) {
        this(SCHEMA_VERSION, graphName, cases);
    }
}
