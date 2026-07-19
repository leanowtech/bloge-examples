package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationEvidence;

import java.util.regex.Pattern;

/**
 * Verified compact observation prepared before atomic terminal publication.
 *
 * @param evidenceFingerprint canonical observation evidence fingerprint
 * @param evidence payload-free observation evidence
 * @param attestationFingerprint canonical complete-attestation fingerprint
 * @param attestation detached observation signature
 */
public record TestSuiteStabilityObservation(
        String evidenceFingerprint,
        TestSuiteStabilityObservationEvidence evidence,
        String attestationFingerprint,
        TestSuiteStabilityObservationAttestation attestation
) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the cross-object identity closure. */
    public TestSuiteStabilityObservation {
        evidenceFingerprint = normalized(evidenceFingerprint);
        attestationFingerprint = normalized(attestationFingerprint);
        if (!FINGERPRINT.matcher(evidenceFingerprint).matches()
                || !FINGERPRINT.matcher(attestationFingerprint).matches()
                || evidence == null || attestation == null
                || !evidence.observationId().equals(attestation.observationId())
                || !evidenceFingerprint.equals(attestation.observationFingerprint())
                || !evidence.source().evidenceFingerprint().equals(
                attestation.sourceEvidenceFingerprint())
                || !evidence.source().attestationFingerprint().equals(
                attestation.sourceAttestationFingerprint())
                || !attestation.terminallyVerifiable()) {
            throw new IllegalArgumentException(
                    "Complete verified suite-stability observation is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
