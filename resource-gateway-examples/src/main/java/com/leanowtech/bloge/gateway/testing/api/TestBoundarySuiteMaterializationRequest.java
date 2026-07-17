package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;

/**
 * Human-confirmed request to materialize selected boundary-plan cases as an immutable suite.
 *
 * @param schemaVersion request protocol version
 * @param suiteId stable destination suite identifier
 * @param classification governed destination classification
 * @param expectedTargetFingerprint exact target reviewed by the caller
 * @param expectedInputSchemaFingerprint exact projected input schema reviewed by the caller
 * @param expectedPlanFingerprint exact generated plan reviewed by the caller
 * @param selectedCaseIds explicit non-empty subset selected by the caller
 * @param acceptCoverageGaps explicit acknowledgement required for a partial plan
 */
public record TestBoundarySuiteMaterializationRequest(
        String schemaVersion,
        String suiteId,
        String classification,
        String expectedTargetFingerprint,
        String expectedInputSchemaFingerprint,
        String expectedPlanFingerprint,
        List<String> selectedCaseIds,
        boolean acceptCoverageGaps
) {
    /** Current boundary-suite materialization request version. */
    public static final String SCHEMA_VERSION = "bloge.testBoundarySuiteMaterializationRequest.v1";

    /** Normalizes scalar values and freezes the caller's explicit selection. */
    public TestBoundarySuiteMaterializationRequest {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteId = normalized(suiteId);
        classification = defaulted(classification, "INTERNAL").toUpperCase(java.util.Locale.ROOT);
        expectedTargetFingerprint = normalized(expectedTargetFingerprint);
        expectedInputSchemaFingerprint = normalized(expectedInputSchemaFingerprint);
        expectedPlanFingerprint = normalized(expectedPlanFingerprint);
        selectedCaseIds = selectedCaseIds == null ? List.of()
                : selectedCaseIds.stream().map(TestBoundarySuiteMaterializationRequest::normalized)
                .toList();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
