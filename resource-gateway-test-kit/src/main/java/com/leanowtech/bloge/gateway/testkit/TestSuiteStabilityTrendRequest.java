package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Exact bounded intent for analyzing retained stability evidence from one immutable suite revision.
 *
 * @param suiteId immutable suite id
 * @param revision positive immutable suite revision
 * @param fingerprint full suite content fingerprint
 * @param fromInclusive inclusive terminal-persistence lower boundary
 * @param toExclusive exclusive terminal-persistence upper boundary
 * @param minimumRuns minimum retained sources required for a conclusion
 * @param maximumRuns hard response and verification source budget
 */
public record TestSuiteStabilityTrendRequest(
        String suiteId,
        long revision,
        String fingerprint,
        Instant fromInclusive,
        Instant toExclusive,
        int minimumRuns,
        int maximumRuns
) {
    /** Smallest meaningful longitudinal sample. */
    public static final int MINIMUM_RUNS = 2;
    /** Largest protocol-supported retained source closure. */
    public static final int MAXIMUM_RUNS = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Normalizes identity fields and rejects invalid or unbounded windows before network I/O. */
    public TestSuiteStabilityTrendRequest {
        suiteId = normalized(suiteId);
        fingerprint = normalized(fingerprint);
        if (suiteId.isBlank() || suiteId.length() > 255 || revision < 1
                || !fingerprint.matches("sha256:[0-9a-f]{64}")
                || fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)
                || minimumRuns < MINIMUM_RUNS || minimumRuns > MAXIMUM_RUNS
                || maximumRuns < minimumRuns || maximumRuns > MAXIMUM_RUNS) {
            throw new IllegalArgumentException(
                    "Complete bounded suite-stability trend request is required");
        }
    }

    /**
     * Returns the exact schema-validated request body sent to Resource Gateway.
     *
     * @return defensive protocol JSON
     */
    public JsonNode toJson() {
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_TREND_ANALYSIS_REQUEST_V1);
        ObjectNode suite = request.putObject("suiteRef");
        suite.put("suiteId", suiteId);
        suite.put("revision", revision);
        suite.put("fingerprint", fingerprint);
        request.put("fromInclusive", fromInclusive.toString());
        request.put("toExclusive", toExclusive.toString());
        request.put("minimumRuns", minimumRuns);
        request.put("maximumRuns", maximumRuns);
        TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityTrendAnalysisRequest");
        return request;
    }

    String requestFingerprint() {
        return EvidenceVerificationSupport.sha256(toJson());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
