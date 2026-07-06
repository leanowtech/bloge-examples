package com.leanowtech.bloge.gateway.visual.testing;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Aggregate result for running multiple stored operator contract-test suites.
 *
 * @param schemaVersion response schema version
 * @param passed whether all suites passed
 * @param totalSuites total suite count
 * @param passedSuites passing suite count
 * @param failedSuites failing suite count
 * @param totalCases total case count
 * @param passedCases passing case count
 * @param failedCases failing case count
 * @param coverage aggregate evidence counters
 * @param results per-suite results
 * @param diagnostics batch diagnostics
 */
public record VisualOperatorContractTestBatchResult(
        String schemaVersion,
        boolean passed,
        int totalSuites,
        int passedSuites,
        int failedSuites,
        int totalCases,
        int passedCases,
        int failedCases,
        VisualOperatorContractTestSuiteResult.Coverage coverage,
        List<VisualOperatorContractTestSuiteRunResult> results,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestBatchResult.v1";

    /**
     * Creates a batch result.
     */
    public VisualOperatorContractTestBatchResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        coverage = coverage == null ? new VisualOperatorContractTestSuiteResult.Coverage(0, 0, 0, 0, 0) : coverage;
        results = results == null ? List.of() : List.copyOf(results);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
