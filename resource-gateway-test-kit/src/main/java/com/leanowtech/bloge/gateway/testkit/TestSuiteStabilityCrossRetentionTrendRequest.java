package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Exact bounded intent for reading one signed compact-observation ledger range.
 *
 * <p>Sequence zero establishes a fresh committed head and therefore requires a blank head pin.
 * Every continuation requires the exact head fingerprint returned by the first page, preventing
 * a client from silently combining pages produced from different ledger heads.</p>
 *
 * @param suiteId immutable suite id
 * @param revision positive immutable suite revision
 * @param fingerprint full suite content fingerprint
 * @param afterSequence exclusive ledger cursor; zero establishes a fresh snapshot
 * @param minimumRuns minimum observations required for a conclusive page trend
 * @param maximumRuns hard response and verification budget
 * @param expectedHeadFingerprint blank for sequence zero; exact pinned head for continuation
 */
public record TestSuiteStabilityCrossRetentionTrendRequest(
        String suiteId,
        long revision,
        String fingerprint,
        long afterSequence,
        int minimumRuns,
        int maximumRuns,
        String expectedHeadFingerprint
) {
    /** Smallest meaningful longitudinal sample. */
    public static final int MINIMUM_RUNS = 2;
    /** Largest protocol-supported compact-observation page. */
    public static final int MAXIMUM_RUNS = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Normalizes identity fields and rejects unpinned continuation before network I/O. */
    public TestSuiteStabilityCrossRetentionTrendRequest {
        suiteId = normalized(suiteId);
        fingerprint = normalized(fingerprint);
        expectedHeadFingerprint = normalized(expectedHeadFingerprint);
        if (suiteId.isBlank() || suiteId.length() > 255 || revision < 1
                || !fingerprint.matches("sha256:[0-9a-f]{64}") || afterSequence < 0
                || minimumRuns < MINIMUM_RUNS || minimumRuns > MAXIMUM_RUNS
                || maximumRuns < minimumRuns || maximumRuns > MAXIMUM_RUNS
                || (!expectedHeadFingerprint.isBlank()
                && !expectedHeadFingerprint.matches("sha256:[0-9a-f]{64}"))
                || (afterSequence == 0 && !expectedHeadFingerprint.isBlank())
                || (afterSequence > 0 && expectedHeadFingerprint.isBlank())) {
            throw new IllegalArgumentException(
                    "Complete head-pinned cross-retention trend request is required");
        }
    }

    /**
     * Starts a fresh range snapshot at the rollout cursor.
     *
     * @param suiteId immutable suite id
     * @param revision immutable suite revision
     * @param fingerprint immutable suite fingerprint
     * @param minimumRuns minimum conclusive observations
     * @param maximumRuns hard page budget
     * @return exact first-page request
     */
    public static TestSuiteStabilityCrossRetentionTrendRequest firstPage(
            String suiteId,
            long revision,
            String fingerprint,
            int minimumRuns,
            int maximumRuns) {
        return new TestSuiteStabilityCrossRetentionTrendRequest(
                suiteId, revision, fingerprint, 0, minimumRuns, maximumRuns, "");
    }

    /**
     * Creates the exact continuation request for one verified page.
     *
     * @param afterSequence last sequence consumed by the caller
     * @param headFingerprint exact committed head established by the first page
     * @return head-pinned continuation request
     */
    public TestSuiteStabilityCrossRetentionTrendRequest continueAfter(
            long afterSequence,
            String headFingerprint) {
        return new TestSuiteStabilityCrossRetentionTrendRequest(
                suiteId, revision, fingerprint, afterSequence, minimumRuns, maximumRuns,
                headFingerprint);
    }

    /**
     * Returns the exact schema-validated request sent to Resource Gateway.
     *
     * @return defensive protocol JSON
     */
    public JsonNode toJson() {
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_CROSS_RETENTION_TREND_REQUEST_V1);
        ObjectNode suite = request.putObject("suiteRef");
        suite.put("suiteId", suiteId);
        suite.put("revision", revision);
        suite.put("fingerprint", fingerprint);
        request.put("afterSequence", afterSequence);
        request.put("minimumRuns", minimumRuns);
        request.put("maximumRuns", maximumRuns);
        request.put("expectedHeadFingerprint", expectedHeadFingerprint);
        TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityCrossRetentionTrendAnalysisRequest");
        return request;
    }

    String requestFingerprint() {
        return EvidenceVerificationSupport.sha256(toJson());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
