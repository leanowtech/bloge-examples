package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;

/**
 * Exact bounded request for a retained-window suite-stability trend analysis.
 *
 * @param schemaVersion exact request wire version
 * @param suiteRef exact immutable suite revision
 * @param fromInclusive inclusive persistence-time lower boundary
 * @param toExclusive exclusive persistence-time upper boundary
 * @param minimumRuns minimum retained sources required for a conclusion
 * @param maximumRuns hard source and response budget
 */
public record TestSuiteStabilityTrendAnalysisRequest(
        String schemaVersion,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        Instant fromInclusive,
        Instant toExclusive,
        int minimumRuns,
        int maximumRuns
) {
    /** Current retained-window request generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityTrendAnalysisRequest.v1";
    /** Smallest meaningful longitudinal sample. */
    public static final int MINIMUM_RUNS = 2;
    /** Hard protocol bound preventing unbounded history projection. */
    public static final int MAXIMUM_RUNS = 100;

    /** Normalizes the version and enforces structural request bounds. */
    public TestSuiteStabilityTrendAnalysisRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || suiteRef == null || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)
                || minimumRuns < MINIMUM_RUNS || minimumRuns > MAXIMUM_RUNS
                || maximumRuns < MINIMUM_RUNS || maximumRuns > MAXIMUM_RUNS
                || minimumRuns > maximumRuns) {
            throw new IllegalArgumentException(
                    "Complete bounded stability trend request is required");
        }
    }
}
