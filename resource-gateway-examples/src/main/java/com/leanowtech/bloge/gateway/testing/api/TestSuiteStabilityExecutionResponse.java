package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;

import java.util.regex.Pattern;

/**
 * Public terminal result for one bounded stability rerun.
 *
 * @param schemaVersion exact response protocol version
 * @param stabilityRunId deterministic analysis id
 * @param evidenceFingerprint canonical stability evidence fingerprint
 * @param evidence payload-free stability evidence
 * @param attestation detached terminal signature
 */
public record TestSuiteStabilityExecutionResponse(
        String schemaVersion,
        String stabilityRunId,
        String evidenceFingerprint,
        TestSuiteStabilityEvidence evidence,
        TestSuiteStabilityAttestation attestation
) {
    /** Historical response version without source-promotion closure. */
    public static final String SCHEMA_VERSION_V1 =
            "bloge.testSuiteStabilityExecutionResponse.v1";
    /** Current stability-execution response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityExecutionResponse.v2";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one complete generation-consistent terminal response. */
    public TestSuiteStabilityExecutionResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        stabilityRunId = stabilityRunId == null ? "" : stabilityRunId.trim();
        evidenceFingerprint = evidenceFingerprint == null ? "" : evidenceFingerprint.trim();
        boolean legacy = SCHEMA_VERSION_V1.equals(schemaVersion);
        if (!java.util.List.of(SCHEMA_VERSION_V1, SCHEMA_VERSION).contains(schemaVersion)
                || evidence == null || attestation == null
                || !stabilityRunId.equals(evidence.stabilityRunId())
                || !stabilityRunId.equals(attestation.stabilityRunId())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !FINGERPRINT.matcher(evidenceFingerprint).matches()
                || !attestation.terminallyVerifiable()
                || legacy != TestSuiteStabilityEvidence.SCHEMA_VERSION_V1.equals(
                evidence.schemaVersion())
                || legacy != TestSuiteStabilityAttestation.SCHEMA_VERSION_V1.equals(
                attestation.schemaVersion())) {
            throw new IllegalArgumentException(
                    "Complete generation-consistent signed stability response is required");
        }
    }
}
