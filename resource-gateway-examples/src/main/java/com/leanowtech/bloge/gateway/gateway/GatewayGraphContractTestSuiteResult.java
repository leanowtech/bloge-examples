package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result for one table-driven resource graph contract-test suite.
 *
 * @param schemaVersion response schema version
 * @param graphName resource graph name
 * @param passed whether every case passed
 * @param totalCases total cases
 * @param passedCases passing cases
 * @param failedCases failing cases
 * @param coverage lightweight evidence counters for industrialization reporting
 * @param results case results
 * @param diagnostics suite-level diagnostics
 */
public record GatewayGraphContractTestSuiteResult(
        String schemaVersion,
        String graphName,
        boolean passed,
        int totalCases,
        int passedCases,
        int failedCases,
        Coverage coverage,
        List<GatewayGraphContractTestCaseResult> results,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestSuiteResult.v1";

    /**
     * Creates a suite result.
     */
    public GatewayGraphContractTestSuiteResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        graphName = graphName == null ? "" : graphName;
        coverage = coverage == null ? new Coverage(0, 0, 0, 0) : coverage;
        results = results == null ? List.of() : List.copyOf(results);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Evidence counters for the executed suite.
     *
     * @param inputSchemaValidated cases whose context passed input schema validation
     * @param contractOutputSchemaValidated cases whose terminal output passed graph output schema validation
     * @param mockedResourceCalls mocked resource invocations observed
     * @param assertionCount assertions evaluated
     */
    public record Coverage(
            int inputSchemaValidated,
            int contractOutputSchemaValidated,
            int mockedResourceCalls,
            int assertionCount
    ) {}
}
