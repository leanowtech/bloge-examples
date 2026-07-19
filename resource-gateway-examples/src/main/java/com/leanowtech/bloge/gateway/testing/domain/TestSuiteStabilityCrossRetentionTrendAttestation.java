package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one cross-retention trend and exact range closure.
 *
 * @param schemaVersion exact attestation wire generation
 * @param signatureStatus bounded signing state
 * @param trendAnalysisId exact analysis identity
 * @param requestFingerprint canonical request identity
 * @param evidenceFingerprint canonical complete evidence identity
 * @param rangeFingerprint exact floor/head/page closure identity
 * @param observationRefs ordered observation and ledger-entry closure
 * @param signedAt signing material time
 * @param keyId verification key identity
 * @param algorithm signature algorithm
 * @param signature detached encoded signature
 * @param independentlyVerifiable whether public key material can verify this signature
 */
public record TestSuiteStabilityCrossRetentionTrendAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String trendAnalysisId,
        String requestFingerprint,
        String evidenceFingerprint,
        String rangeFingerprint,
        List<ObservationRef> observationRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current cross-retention trend attestation generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityCrossRetentionTrendAttestation.v1";
    private static final Pattern TREND_ID =
            Pattern.compile("stability-cross-retention-trend-[a-f0-9]{64}");
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("stability-observation-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Closed signature states without provider diagnostics. */
    public enum SignatureStatus {
        /** Signature was created and immediately verified. */
        VERIFIED,
        /** Signing or public-key verification authority was unavailable. */
        VERIFICATION_UNAVAILABLE
    }

    /**
     * One ordered compact-observation closure coordinate.
     *
     * @param sequence exact ledger sequence
     * @param observationId deterministic compact observation identity
     * @param observationFingerprint canonical observation evidence identity
     * @param observationAttestationFingerprint canonical observation signature identity
     * @param entryFingerprint canonical producer ledger envelope identity
     */
    public record ObservationRef(
            long sequence,
            String observationId,
            String observationFingerprint,
            String observationAttestationFingerprint,
            String entryFingerprint
    ) {
        /** Validates one complete ordered source coordinate. */
        public ObservationRef {
            observationId = normalized(observationId);
            observationFingerprint = normalized(observationFingerprint);
            observationAttestationFingerprint = normalized(
                    observationAttestationFingerprint);
            entryFingerprint = normalized(entryFingerprint);
            if (sequence < 1 || !OBSERVATION_ID.matcher(observationId).matches()
                    || !fingerprint(observationFingerprint)
                    || !fingerprint(observationAttestationFingerprint)
                    || !fingerprint(entryFingerprint)) {
                throw new IllegalArgumentException(
                        "Complete cross-retention observation reference is required");
            }
        }
    }

    /** Enforces verified and fail-closed signature shapes plus ordered source uniqueness. */
    public TestSuiteStabilityCrossRetentionTrendAttestation {
        schemaVersion = normalized(schemaVersion);
        trendAnalysisId = normalized(trendAnalysisId);
        requestFingerprint = normalized(requestFingerprint);
        evidenceFingerprint = normalized(evidenceFingerprint);
        rangeFingerprint = normalized(rangeFingerprint);
        observationRefs = observationRefs == null ? List.of() : List.copyOf(observationRefs);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean identityValid = SCHEMA_VERSION.equals(schemaVersion)
                && TREND_ID.matcher(trendAnalysisId).matches()
                && fingerprint(requestFingerprint) && fingerprint(evidenceFingerprint)
                && fingerprint(rangeFingerprint) && ordered(observationRefs);
        boolean verified = signatureStatus == SignatureStatus.VERIFIED
                && signedAt != null && !Instant.EPOCH.equals(signedAt)
                && !keyId.isBlank() && "Ed25519".equals(algorithm)
                && !signature.isBlank() && independentlyVerifiable;
        boolean unavailable = signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && signedAt == null && keyId.isBlank() && algorithm.isBlank()
                && signature.isBlank() && !independentlyVerifiable;
        if (!identityValid || (!verified && !unavailable)) {
            throw new IllegalArgumentException(
                    "Complete cross-retention trend attestation is required");
        }
    }

    /** @return whether this attestation can cross the public evidence boundary */
    public boolean terminallyVerifiable() {
        return signatureStatus == SignatureStatus.VERIFIED && independentlyVerifiable;
    }

    /** Creates bounded unsigned material that can never cross the response boundary. */
    public static TestSuiteStabilityCrossRetentionTrendAttestation unavailable(
            TestSuiteStabilityCrossRetentionTrendEvidence evidence,
            String evidenceFingerprint,
            List<ObservationRef> observationRefs) {
        return new TestSuiteStabilityCrossRetentionTrendAttestation(
                SCHEMA_VERSION, SignatureStatus.VERIFICATION_UNAVAILABLE,
                evidence.trendAnalysisId(), evidence.requestFingerprint(), evidenceFingerprint,
                evidence.range().rangeFingerprint(), observationRefs,
                null, "", "", "", false);
    }

    private static boolean ordered(List<ObservationRef> values) {
        long previous = -1;
        for (ObservationRef value : values) {
            if (value == null || value.sequence() <= previous) {
                return false;
            }
            previous = value.sequence();
        }
        return values.stream().map(ObservationRef::observationId).distinct().count()
                == values.size();
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
