package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendEvidence;

import java.util.regex.Pattern;

/**
 * Signed portable response for one exact compact-observation trend range.
 *
 * @param schemaVersion exact response wire generation
 * @param trendAnalysisId deterministic analysis identity
 * @param evidenceFingerprint canonical evidence identity
 * @param evidence complete range and derived trend evidence
 * @param attestation detached range-closing signature
 */
public record TestSuiteStabilityCrossRetentionTrendAnalysisResponse(
        String schemaVersion,
        String trendAnalysisId,
        String evidenceFingerprint,
        TestSuiteStabilityCrossRetentionTrendEvidence evidence,
        TestSuiteStabilityCrossRetentionTrendAttestation attestation
) {
    /** Current signed cross-retention trend response generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityCrossRetentionTrendAnalysisResponse.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates complete generation and signature identity closure. */
    public TestSuiteStabilityCrossRetentionTrendAnalysisResponse {
        schemaVersion = normalized(schemaVersion);
        trendAnalysisId = normalized(trendAnalysisId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion) || evidence == null || attestation == null
                || !trendAnalysisId.equals(evidence.trendAnalysisId())
                || !trendAnalysisId.equals(attestation.trendAnalysisId())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !FINGERPRINT.matcher(evidenceFingerprint).matches()
                || !evidence.requestFingerprint().equals(attestation.requestFingerprint())
                || !evidence.range().rangeFingerprint().equals(
                attestation.rangeFingerprint())
                || !attestation.terminallyVerifiable()) {
            throw new IllegalArgumentException(
                    "Complete signed cross-retention trend response is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
