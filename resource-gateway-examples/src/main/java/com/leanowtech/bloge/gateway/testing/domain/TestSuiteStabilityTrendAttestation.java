package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature for one retained-window trend analysis.
 *
 * @param schemaVersion exact attestation protocol version
 * @param signatureStatus signature trust state
 * @param trendAnalysisId deterministic analysis identity
 * @param requestFingerprint canonical request fingerprint
 * @param evidenceFingerprint canonical trend-evidence fingerprint
 * @param sourceEvidenceRefs exact ordered stability-source closure
 * @param signedAt signature time
 * @param keyId verification-key identity
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable complete-signature claim
 */
public record TestSuiteStabilityTrendAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String trendAnalysisId,
        String requestFingerprint,
        String evidenceFingerprint,
        List<SourceEvidenceRef> sourceEvidenceRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current trend-attestation generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityTrendAttestation.v1";
    private static final Pattern TREND_ID = Pattern.compile("stability-trend-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Closed signature states without provider diagnostics. */
    public enum SignatureStatus {
        VERIFIED,
        VERIFICATION_UNAVAILABLE
    }

    /**
     * Exact immutable source identity required for independent reconstruction.
     *
     * @param stabilityRunId source stability identity
     * @param evidenceFingerprint source evidence identity
     * @param attestationFingerprint exact source signature-object identity
     */
    public record SourceEvidenceRef(
            String stabilityRunId,
            String evidenceFingerprint,
            String attestationFingerprint
    ) {
        /** Validates one complete source coordinate. */
        public SourceEvidenceRef {
            stabilityRunId = normalized(stabilityRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            attestationFingerprint = normalized(attestationFingerprint);
            if (!stabilityRunId.matches("stability-[a-f0-9]{64}")
                    || !fingerprint(evidenceFingerprint)
                    || !fingerprint(attestationFingerprint)) {
                throw new IllegalArgumentException("Complete trend source identity required");
            }
        }
    }

    /** Normalizes signature material and derives independent verifiability. */
    public TestSuiteStabilityTrendAttestation {
        schemaVersion = normalized(schemaVersion);
        signatureStatus = signatureStatus == null
                ? SignatureStatus.VERIFICATION_UNAVAILABLE : signatureStatus;
        trendAnalysisId = normalized(trendAnalysisId);
        requestFingerprint = normalized(requestFingerprint);
        evidenceFingerprint = normalized(evidenceFingerprint);
        sourceEvidenceRefs = sourceEvidenceRefs == null ? List.of()
                : List.copyOf(sourceEvidenceRefs);
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        independentlyVerifiable = signatureStatus == SignatureStatus.VERIFIED
                && completeIdentity(trendAnalysisId, requestFingerprint, evidenceFingerprint)
                && !keyId.isBlank() && !algorithm.isBlank() && !signature.isBlank()
                && !Instant.EPOCH.equals(signedAt);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !completeIdentity(trendAnalysisId, requestFingerprint, evidenceFingerprint)
                || sourceEvidenceRefs.stream().map(SourceEvidenceRef::stabilityRunId)
                .distinct().count() != sourceEvidenceRefs.size()
                || (signatureStatus == SignatureStatus.VERIFIED && !independentlyVerifiable)) {
            throw new IllegalArgumentException("Complete trend attestation material required");
        }
    }

    /** Creates a fail-closed manifest when no trusted signature can be produced. */
    public static TestSuiteStabilityTrendAttestation unavailable(
            TestSuiteStabilityTrendEvidence evidence,
            String evidenceFingerprint,
            List<SourceEvidenceRef> sources) {
        return new TestSuiteStabilityTrendAttestation(
                SCHEMA_VERSION, SignatureStatus.VERIFICATION_UNAVAILABLE,
                evidence.trendAnalysisId(), evidence.requestFingerprint(), evidenceFingerprint,
                sources, Instant.EPOCH, "", "", "", false);
    }

    /** @return true only for a complete verified detached signature */
    public boolean terminallyVerifiable() {
        return independentlyVerifiable;
    }

    private static boolean completeIdentity(
            String trendAnalysisId,
            String requestFingerprint,
            String evidenceFingerprint) {
        return TREND_ID.matcher(normalized(trendAnalysisId)).matches()
                && fingerprint(requestFingerprint) && fingerprint(evidenceFingerprint);
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
