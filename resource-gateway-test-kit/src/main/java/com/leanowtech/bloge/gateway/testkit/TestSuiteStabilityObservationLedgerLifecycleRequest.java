package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Exact bounded intent for reading one signed observation-ledger floor lifecycle page.
 *
 * <p>A first page starts after generation zero with blank pins. Every continuation carries the
 * current floor and head established by the first page, preventing a caller from joining pages
 * across an append or retirement race.</p>
 *
 * @param suiteId immutable suite id
 * @param revision positive immutable suite revision
 * @param fingerprint full suite content fingerprint
 * @param afterRetirementGeneration exclusive retirement-generation cursor
 * @param maximumRetirements hard page and nested archive budget
 * @param expectedCurrentFloorFingerprint blank for the first page; snapshot floor thereafter
 * @param expectedHeadFingerprint blank for the first page; snapshot head thereafter
 */
public record TestSuiteStabilityObservationLedgerLifecycleRequest(
        String suiteId,
        long revision,
        String fingerprint,
        long afterRetirementGeneration,
        int maximumRetirements,
        String expectedCurrentFloorFingerprint,
        String expectedHeadFingerprint
) {
    /** Largest protocol-supported retirement page. */
    public static final int MAXIMUM_RETIREMENTS = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Normalizes identity fields and rejects unpinned continuation before network I/O. */
    public TestSuiteStabilityObservationLedgerLifecycleRequest {
        suiteId = normalized(suiteId);
        fingerprint = normalized(fingerprint);
        expectedCurrentFloorFingerprint = normalized(expectedCurrentFloorFingerprint);
        expectedHeadFingerprint = normalized(expectedHeadFingerprint);
        boolean firstPage = afterRetirementGeneration == 0
                && expectedCurrentFloorFingerprint.isBlank()
                && expectedHeadFingerprint.isBlank();
        boolean continuation = afterRetirementGeneration > 0
                && validFingerprint(expectedCurrentFloorFingerprint)
                && validFingerprint(expectedHeadFingerprint);
        if (suiteId.isBlank() || suiteId.length() > 255 || revision < 1
                || !validFingerprint(fingerprint)
                || maximumRetirements < 1
                || maximumRetirements > MAXIMUM_RETIREMENTS
                || (!firstPage && !continuation)) {
            throw new IllegalArgumentException(
                    "Complete pinned observation-lifecycle request is required");
        }
    }

    /**
     * Creates a fresh lifecycle snapshot starting at rollout generation zero.
     *
     * @param suiteId stable suite identity
     * @param revision exact immutable suite revision
     * @param fingerprint exact suite content fingerprint
     * @param maximumRetirements positive bounded page size
     * @return generation-zero request with blank snapshot pins
     */
    public static TestSuiteStabilityObservationLedgerLifecycleRequest firstPage(
            String suiteId,
            long revision,
            String fingerprint,
            int maximumRetirements) {
        return new TestSuiteStabilityObservationLedgerLifecycleRequest(
                suiteId, revision, fingerprint, 0, maximumRetirements, "", "");
    }

    /**
     * Creates the exact continuation for a verified page.
     *
     * @param page verified page with another retirement generation
     * @return floor/head-pinned continuation
     */
    public TestSuiteStabilityObservationLedgerLifecycleRequest continueAfter(
            TestSuiteStabilityObservationLedgerLifecyclePage page) {
        if (page == null || !page.hasMore()
                || !suiteId.equals(page.request().suiteId())
                || revision != page.request().revision()
                || !fingerprint.equals(page.request().fingerprint())) {
            throw new IllegalArgumentException(
                    "A matching non-terminal lifecycle page is required");
        }
        return new TestSuiteStabilityObservationLedgerLifecycleRequest(
                suiteId, revision, fingerprint,
                page.terminalFloor().retirementGeneration(), maximumRetirements,
                page.currentFloor().floorFingerprint(), page.head().headFingerprint());
    }

    /**
     * Returns the exact schema-validated request sent to Resource Gateway.
     *
     * @return defensive request JSON satisfying the authoritative Schema
     */
    public JsonNode toJson() {
        ObjectNode request = JSON.createObjectNode();
        request.put("schemaVersion",
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_REQUEST_V1);
        ObjectNode suite = request.putObject("suiteRef");
        suite.put("suiteId", suiteId);
        suite.put("revision", revision);
        suite.put("fingerprint", fingerprint);
        request.put("afterRetirementGeneration", afterRetirementGeneration);
        request.put("maximumRetirements", maximumRetirements);
        request.put("expectedCurrentFloorFingerprint", expectedCurrentFloorFingerprint);
        request.put("expectedHeadFingerprint", expectedHeadFingerprint);
        TestingProtocolSchemaValidator.require(
                request, "testSuiteStabilityObservationLedgerLifecyclePageRequest");
        return request;
    }

    String requestFingerprint() {
        return EvidenceVerificationSupport.sha256(toJson());
    }

    private static boolean validFingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
