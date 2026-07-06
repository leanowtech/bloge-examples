package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result for a batch run of stored graph contract-test suites.
 *
 * @param schemaVersion response schema version
 * @param passed whether all selected suites passed
 * @param totalSuites total suites
 * @param passedSuites passing suites
 * @param failedSuites failing suites
 * @param totalCases total cases across suites
 * @param passedCases passing cases across suites
 * @param failedCases failing cases across suites
 * @param coverage aggregate evidence counters
 * @param results per-suite results
 * @param diagnostics batch-level diagnostics
 */
public record GatewayGraphContractTestBatchResult(
        String schemaVersion,
        boolean passed,
        int totalSuites,
        int passedSuites,
        int failedSuites,
        int totalCases,
        int passedCases,
        int failedCases,
        GatewayGraphContractTestSuiteResult.Coverage coverage,
        List<GatewayGraphContractTestSuiteRunResult> results,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestBatchResult.v1";

    /**
     * Creates a batch result.
     */
    public GatewayGraphContractTestBatchResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        totalSuites = Math.max(totalSuites, 0);
        passedSuites = Math.max(passedSuites, 0);
        failedSuites = Math.max(failedSuites, 0);
        totalCases = Math.max(totalCases, 0);
        passedCases = Math.max(passedCases, 0);
        failedCases = Math.max(failedCases, 0);
        coverage = coverage == null
                ? new GatewayGraphContractTestSuiteResult.Coverage(0, 0, 0, 0)
                : coverage;
        results = results == null ? List.of() : List.copyOf(results);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
