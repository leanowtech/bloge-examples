package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;

/**
 * Public response for one idempotent immutable suite execution.
 *
 * @param schemaVersion suite-execution response protocol version
 * @param suiteRunId persistent aggregate run identifier
 * @param evidenceFingerprint canonical terminal evidence fingerprint; blank while still running
 * @param evidence aggregate evidence without duplicated child payloads
 * @param attestation signed checkpoint or terminal aggregate closure
 */
public record TestSuiteExecutionResponse(
        String schemaVersion,
        String suiteRunId,
        String evidenceFingerprint,
        TestSuiteRunEvidence evidence,
        TestSuiteRunAttestation attestation
) {
    /** Historical response protocol without a suite-run attestation. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteExecutionResponse.v1";
    /** Current suite-execution response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteExecutionResponse.v2";

    /** Applies current protocol defaults to service-created responses. */
    public TestSuiteExecutionResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        suiteRunId = suiteRunId == null ? "" : suiteRunId.trim();
        evidenceFingerprint = evidenceFingerprint == null ? "" : evidenceFingerprint.trim();
        attestation = attestation == null ? TestSuiteRunAttestation.unsigned() : attestation;
    }

    /** Creates a migration-compatible v1 response without an attestation. */
    public TestSuiteExecutionResponse(String schemaVersion, String suiteRunId,
                                      String evidenceFingerprint, TestSuiteRunEvidence evidence) {
        this(schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION_V1 : schemaVersion,
                suiteRunId, evidenceFingerprint, evidence, TestSuiteRunAttestation.unsigned());
    }
}
