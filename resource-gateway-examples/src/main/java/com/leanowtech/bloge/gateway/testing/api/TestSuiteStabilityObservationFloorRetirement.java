package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;

import java.util.regex.Pattern;

/**
 * Complete signed floor-retirement record committed beside its local archive segment.
 *
 * @param evidenceFingerprint canonical evidence identity
 * @param evidence exact floor/head/archive/policy closure
 * @param attestationFingerprint canonical detached-attestation identity
 * @param attestation verified detached signature
 * @param retirementFingerprint canonical complete-record identity excluding itself
 */
public record TestSuiteStabilityObservationFloorRetirement(
        String evidenceFingerprint,
        TestSuiteStabilityObservationFloorRetirementEvidence evidence,
        String attestationFingerprint,
        TestSuiteStabilityObservationFloorRetirementAttestation attestation,
        String retirementFingerprint
) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates all cross-object retirement references before persistence. */
    public TestSuiteStabilityObservationFloorRetirement {
        evidenceFingerprint = normalized(evidenceFingerprint);
        attestationFingerprint = normalized(attestationFingerprint);
        retirementFingerprint = normalized(retirementFingerprint);
        if (!fingerprint(evidenceFingerprint) || evidence == null
                || !fingerprint(attestationFingerprint) || attestation == null
                || !fingerprint(retirementFingerprint)
                || !evidence.retirementId().equals(attestation.retirementId())
                || !evidenceFingerprint.equals(attestation.evidenceFingerprint())
                || !evidence.archiveSegment().segmentFingerprint().equals(
                attestation.archiveSegmentFingerprint())
                || !evidence.previousFloor().floorFingerprint().equals(
                attestation.previousFloorFingerprint())
                || !evidence.pinnedHead().headFingerprint().equals(
                attestation.pinnedHeadFingerprint())
                || !attestation.terminallyVerifiable()) {
            throw new IllegalArgumentException(
                    "Complete signed suite-stability floor retirement is required");
        }
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
