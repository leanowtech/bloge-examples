package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;

import java.util.regex.Pattern;

/**
 * Signed payload-free response for one retained-window trend analysis.
 *
 * @param schemaVersion exact response wire version
 * @param trendAnalysisId deterministic analysis identity
 * @param evidenceFingerprint canonical trend-evidence identity
 * @param evidence complete derived trend evidence
 * @param attestation detached signature over evidence and source closure
 */
public record TestSuiteStabilityTrendAnalysisResponse(
        String schemaVersion,
        String trendAnalysisId,
        String evidenceFingerprint,
        TestSuiteStabilityTrendEvidence evidence,
        TestSuiteStabilityTrendAttestation attestation
) {
    /** Current response generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityTrendAnalysisResponse.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates a complete generation-consistent signed response. */
    public TestSuiteStabilityTrendAnalysisResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        trendAnalysisId = trendAnalysisId == null ? "" : trendAnalysisId.trim();
        evidenceFingerprint = evidenceFingerprint == null ? "" : evidenceFingerprint.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || evidence == null || attestation == null
                || !trendAnalysisId.equals(evidence.trendAnalysisId())
                || !trendAnalysisId.equals(attestation.trendAnalysisId())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !FINGERPRINT.matcher(evidenceFingerprint).matches()
                || !attestation.terminallyVerifiable()) {
            throw new IllegalArgumentException(
                    "Complete generation-consistent signed trend response required");
        }
    }
}
