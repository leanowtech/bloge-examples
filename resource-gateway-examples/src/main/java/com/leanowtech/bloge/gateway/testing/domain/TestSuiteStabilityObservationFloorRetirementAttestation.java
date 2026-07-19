package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Detached signature over one exact compact-observation floor-retirement evidence object.
 *
 * @param schemaVersion exact attestation generation
 * @param signatureStatus closed signing status
 * @param retirementId exact deterministic retirement identity
 * @param evidenceFingerprint canonical retirement evidence identity
 * @param archiveSegmentFingerprint canonical local archive identity
 * @param previousFloorFingerprint exact predecessor floor CAS identity
 * @param pinnedHeadFingerprint exact committed head observed during planning
 * @param signedAt signature material time
 * @param keyId verification key identity
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable whether a consumer can verify without producer state
 */
public record TestSuiteStabilityObservationFloorRetirementAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String retirementId,
        String evidenceFingerprint,
        String archiveSegmentFingerprint,
        String previousFloorFingerprint,
        String pinnedHeadFingerprint,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current floor-retirement signature generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationFloorRetirementAttestation.v1";
    private static final Pattern RETIREMENT_ID =
            Pattern.compile("stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects incomplete or self-contradictory signature manifests. */
    public TestSuiteStabilityObservationFloorRetirementAttestation {
        schemaVersion = normalized(schemaVersion);
        retirementId = normalized(retirementId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        archiveSegmentFingerprint = normalized(archiveSegmentFingerprint);
        previousFloorFingerprint = normalized(previousFloorFingerprint);
        pinnedHeadFingerprint = normalized(pinnedHeadFingerprint);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean verified = signatureStatus == SignatureStatus.VERIFIED
                && signedAt != null && !Instant.EPOCH.equals(signedAt)
                && !keyId.isBlank() && "Ed25519".equals(algorithm)
                && !signature.isBlank() && independentlyVerifiable;
        if (!SCHEMA_VERSION.equals(schemaVersion) || signatureStatus == null
                || !RETIREMENT_ID.matcher(retirementId).matches()
                || !fingerprint(evidenceFingerprint)
                || !fingerprint(archiveSegmentFingerprint)
                || !fingerprint(previousFloorFingerprint)
                || !fingerprint(pinnedHeadFingerprint) || !verified) {
            throw new IllegalArgumentException(
                    "Verified suite-stability floor retirement attestation is required");
        }
    }

    /** @return whether this manifest is suitable for independent verification */
    public boolean terminallyVerifiable() {
        return signatureStatus == SignatureStatus.VERIFIED && independentlyVerifiable;
    }

    /** Closed signature state for the internal retirement commit protocol. */
    public enum SignatureStatus {
        /** Signature was generated and immediately verified. */
        VERIFIED
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
