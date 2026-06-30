package com.leanowtech.bloge.gateway.visual.golden;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Result of executing all golden cases bound to one visual graph publication.
 *
 * @param passed whether every case passed and the suite had at least one case
 * @param publicationId immutable publication id
 * @param totalCases number of executed golden cases
 * @param passedCases number of passing golden cases
 * @param failedCases number of failing golden cases
 * @param results per-case run results
 * @param diagnostics suite-level diagnostics
 */
public record VisualGraphGoldenSuiteRunResult(
        boolean passed,
        String publicationId,
        int totalCases,
        int passedCases,
        int failedCases,
        List<VisualGraphGoldenCaseRunResult> results,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a suite result.
     */
    public VisualGraphGoldenSuiteRunResult {
        publicationId = publicationId == null ? "" : publicationId;
        results = results == null ? List.of() : List.copyOf(results);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Builds a result and derives counts from the case results.
     *
     * @param publicationId immutable publication id
     * @param results per-case results
     * @param diagnostics suite-level diagnostics
     * @return suite result
     */
    public static VisualGraphGoldenSuiteRunResult from(String publicationId,
                                                       List<VisualGraphGoldenCaseRunResult> results,
                                                       List<VisualDiagnostic> diagnostics) {
        List<VisualGraphGoldenCaseRunResult> safeResults = results == null ? List.of() : List.copyOf(results);
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        int passedCases = (int) safeResults.stream()
                .filter(VisualGraphGoldenCaseRunResult::passed)
                .count();
        int totalCases = safeResults.size();
        int failedCases = totalCases - passedCases;
        boolean passed = totalCases > 0
                && failedCases == 0
                && safeDiagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualGraphGoldenSuiteRunResult(
                passed,
                publicationId,
                totalCases,
                passedCases,
                failedCases,
                safeResults,
                safeDiagnostics
        );
    }
}
