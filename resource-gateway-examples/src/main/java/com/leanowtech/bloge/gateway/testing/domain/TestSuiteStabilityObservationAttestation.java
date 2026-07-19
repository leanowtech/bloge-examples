package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one compact stability observation.
 *
 * @param schemaVersion exact attestation protocol generation
 * @param signatureStatus bounded signing result
 * @param observationId exact observation identity
 * @param observationFingerprint canonical observation evidence fingerprint
 * @param sourceEvidenceFingerprint original stability evidence identity
 * @param sourceAttestationFingerprint original stability attestation identity
 * @param signedAt signing-authority time
 * @param keyId verification key identity
 * @param algorithm signature algorithm
 * @param signature detached encoded signature
 * @param independentlyVerifiable whether public verification material can verify the signature
 */
public record TestSuiteStabilityObservationAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String observationId,
        String observationFingerprint,
        String sourceEvidenceFingerprint,
        String sourceAttestationFingerprint,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current observation attestation generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationAttestation.v1";
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Closed signing outcomes without provider diagnostics. */
    public enum SignatureStatus {
        /** Signature was created and immediately verified. */
        VERIFIED,
        /** The signing or verification authority was unavailable. */
        VERIFICATION_UNAVAILABLE
    }

    /** Enforces generation-consistent verified and fail-closed shapes. */
    public TestSuiteStabilityObservationAttestation {
        schemaVersion = normalized(schemaVersion);
        observationId = normalized(observationId);
        observationFingerprint = normalized(observationFingerprint);
        sourceEvidenceFingerprint = normalized(sourceEvidenceFingerprint);
        sourceAttestationFingerprint = normalized(sourceAttestationFingerprint);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean identityValid = SCHEMA_VERSION.equals(schemaVersion)
                && OBSERVATION_ID.matcher(observationId).matches()
                && fingerprint(observationFingerprint)
                && fingerprint(sourceEvidenceFingerprint)
                && fingerprint(sourceAttestationFingerprint);
        boolean verified = signatureStatus == SignatureStatus.VERIFIED
                && signedAt != null && !Instant.EPOCH.equals(signedAt)
                && !keyId.isBlank() && "Ed25519".equals(algorithm)
                && !signature.isBlank() && independentlyVerifiable;
        boolean unavailable = signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && signedAt == null && keyId.isBlank() && algorithm.isBlank()
                && signature.isBlank() && !independentlyVerifiable;
        if (!identityValid || (!verified && !unavailable)) {
            throw new IllegalArgumentException(
                    "Complete suite-stability observation attestation is required");
        }
    }

    /** @return whether this attestation can cross the durable observation boundary */
    public boolean terminallyVerifiable() {
        return signatureStatus == SignatureStatus.VERIFIED && independentlyVerifiable;
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
