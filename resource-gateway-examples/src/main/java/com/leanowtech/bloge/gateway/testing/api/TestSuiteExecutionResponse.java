package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

/**
 * Public response for one idempotent immutable suite execution.
 *
 * @param schemaVersion suite-execution response protocol version
 * @param suiteRunId persistent aggregate run identifier
 * @param evidenceFingerprint canonical terminal evidence fingerprint; blank while still running
 * @param evidence aggregate evidence without duplicated child payloads
 */
public record TestSuiteExecutionResponse(
        String schemaVersion,
        String suiteRunId,
        String evidenceFingerprint,
        TestSuiteRunEvidence evidence
) {
    /** Current suite-execution response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteExecutionResponse.v1";

    /** Applies current protocol defaults to service-created responses. */
    public TestSuiteExecutionResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        suiteRunId = suiteRunId == null ? "" : suiteRunId.trim();
        evidenceFingerprint = evidenceFingerprint == null ? "" : evidenceFingerprint.trim();
    }
}
