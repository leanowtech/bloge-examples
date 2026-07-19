package com.leanowtech.bloge.gateway.testing.api;

import java.util.regex.Pattern;

/**
 * Exact bounded cursor request for a compact-observation stability trend.
 *
 * <p>A blank {@code expectedHeadFingerprint} asks the producer to establish a fresh snapshot.
 * Supplying the fingerprint returned by an earlier page pins subsequent pages to that exact head;
 * an append then produces a conflict instead of silently mixing snapshots.</p>
 *
 * @param schemaVersion exact request wire generation
 * @param suiteRef exact immutable suite revision
 * @param afterSequence exclusive committed ledger cursor; zero starts at the retained floor
 * @param minimumRuns minimum observations required for a conclusive trend
 * @param maximumRuns hard response and verification budget
 * @param expectedHeadFingerprint optional exact snapshot head identity
 */
public record TestSuiteStabilityCrossRetentionTrendAnalysisRequest(
        String schemaVersion,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        long afterSequence,
        int minimumRuns,
        int maximumRuns,
        String expectedHeadFingerprint
) {
    /** Current cross-retention cursor request generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityCrossRetentionTrendAnalysisRequest.v1";
    /** Smallest meaningful longitudinal sample. */
    public static final int MINIMUM_RUNS = 2;
    /** Hard protocol bound preventing unbounded evidence projection. */
    public static final int MAXIMUM_RUNS = 100;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes optional material and enforces exact bounded cursor semantics. */
    public TestSuiteStabilityCrossRetentionTrendAnalysisRequest {
        schemaVersion = normalized(schemaVersion);
        expectedHeadFingerprint = normalized(expectedHeadFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || suiteRef == null || normalized(suiteRef.suiteId()).isBlank()
                || suiteRef.revision() < 1
                || !FINGERPRINT.matcher(normalized(suiteRef.fingerprint())).matches()
                || afterSequence < 0
                || minimumRuns < MINIMUM_RUNS || minimumRuns > MAXIMUM_RUNS
                || maximumRuns < MINIMUM_RUNS || maximumRuns > MAXIMUM_RUNS
                || minimumRuns > maximumRuns
                || !expectedHeadFingerprint.isBlank()
                && !FINGERPRINT.matcher(expectedHeadFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Complete bounded cross-retention trend request is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
