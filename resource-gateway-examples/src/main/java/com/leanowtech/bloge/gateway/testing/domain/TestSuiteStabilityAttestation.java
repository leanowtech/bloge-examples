package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one terminal stability analysis.
 *
 * <p>The signed material binds the parent request, complete stability evidence fingerprint, and
 * ordered source suite-run closure. It does not authorize quarantine or publication; those remain
 * separate governance actions.</p>
 *
 * @param schemaVersion exact attestation protocol version
 * @param signatureStatus signature trust state
 * @param stabilityRunId deterministic stability analysis id
 * @param suiteRef exact immutable suite revision
 * @param requestFingerprint canonical parent request fingerprint
 * @param evidenceFingerprint canonical stability evidence fingerprint
 * @param sourceSuiteEvidenceRefs ordered source suite-run closure
 * @param signedAt signature time
 * @param keyId verification-key id
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable complete-signature claim derived by the constructor
 */
public record TestSuiteStabilityAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String stabilityRunId,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String requestFingerprint,
        String evidenceFingerprint,
        List<SourceSuiteEvidenceRef> sourceSuiteEvidenceRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current stability-attestation protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityAttestation.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");

    /** Signature state persisted without provider-specific diagnostics. */
    public enum SignatureStatus {
        VERIFIED,
        UNSIGNED,
        VERIFICATION_UNAVAILABLE
    }

    /**
     * Exact source aggregate identity bound into attempt order.
     *
     * @param attempt one-based stability attempt
     * @param suiteRunId durable source suite-run id
     * @param aggregateEvidenceFingerprint signed source aggregate fingerprint
     */
    public record SourceSuiteEvidenceRef(
            int attempt,
            String suiteRunId,
            String aggregateEvidenceFingerprint
    ) {
        /** Normalizes and validates one complete source reference. */
        public SourceSuiteEvidenceRef {
            suiteRunId = normalized(suiteRunId);
            aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
            if (attempt < 1 || suiteRunId.isBlank()
                    || !fingerprint(aggregateEvidenceFingerprint)) {
                throw new IllegalArgumentException(
                        "Complete stability source evidence identity is required");
            }
        }
    }

    /** Normalizes protocol fields and derives independent verifiability. */
    public TestSuiteStabilityAttestation {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        signatureStatus = signatureStatus == null ? SignatureStatus.UNSIGNED : signatureStatus;
        stabilityRunId = normalized(stabilityRunId);
        requestFingerprint = normalized(requestFingerprint);
        evidenceFingerprint = normalized(evidenceFingerprint);
        sourceSuiteEvidenceRefs = sourceSuiteEvidenceRefs == null
                ? List.of() : List.copyOf(sourceSuiteEvidenceRefs);
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        independentlyVerifiable = signatureStatus == SignatureStatus.VERIFIED
                && completeIdentity(stabilityRunId, suiteRef, requestFingerprint,
                evidenceFingerprint) && !keyId.isBlank() && !algorithm.isBlank()
                && !signature.isBlank() && !Instant.EPOCH.equals(signedAt);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stability attestation schemaVersion");
        }
        if (signatureStatus == SignatureStatus.VERIFIED && !independentlyVerifiable) {
            throw new IllegalArgumentException(
                    "Verified stability attestation requires complete signed material");
        }
        if (signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && !completeIdentity(stabilityRunId, suiteRef, requestFingerprint,
                evidenceFingerprint)) {
            throw new IllegalArgumentException(
                    "Unavailable stability attestation requires complete analysis identity");
        }
    }

    /**
     * Creates a fail-closed manifest bound to complete unsigned analysis material.
     *
     * @param evidence exact stability evidence
     * @param requestFingerprint canonical request fingerprint
     * @param evidenceFingerprint canonical evidence fingerprint
     * @param sources exact source closure
     * @return unavailable attestation
     */
    public static TestSuiteStabilityAttestation unavailable(
            TestSuiteStabilityEvidence evidence,
            String requestFingerprint,
            String evidenceFingerprint,
            List<SourceSuiteEvidenceRef> sources) {
        return new TestSuiteStabilityAttestation("", SignatureStatus.VERIFICATION_UNAVAILABLE,
                evidence.stabilityRunId(), evidence.suiteRef(), requestFingerprint,
                evidenceFingerprint, sources, Instant.EPOCH, "", "", "", false);
    }

    /** @return true only for a complete verified terminal analysis signature */
    public boolean terminallyVerifiable() {
        return independentlyVerifiable;
    }

    private static boolean completeIdentity(
            String stabilityRunId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String requestFingerprint,
            String evidenceFingerprint) {
        return STABILITY_RUN_ID.matcher(normalized(stabilityRunId)).matches()
                && suiteRef != null && !normalized(suiteRef.suiteId()).isBlank()
                && suiteRef.revision() > 0 && fingerprint(suiteRef.fingerprint())
                && fingerprint(requestFingerprint) && fingerprint(evidenceFingerprint);
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
