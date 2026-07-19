package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
    /** Historical attestation version without source-promotion closure. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteStabilityAttestation.v1";
    /** Deterministic attestation version with source-promotion closure. */
    public static final String SCHEMA_VERSION_V2 = "bloge.testSuiteStabilityAttestation.v2";
    /** Legacy attestation version over zero-event statistical v3 evidence. */
    public static final String SCHEMA_VERSION_V3 = "bloge.testSuiteStabilityAttestation.v3";
    /** Current attestation version over baseline-conditional statistical v4 evidence. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityAttestation.v4";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

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
     * @param sourcePromotionStatus source suite release-promotion status
     * @param sourcePromotionReasons exact bounded source promotion reasons
     */
    public record SourceSuiteEvidenceRef(
            int attempt,
            String suiteRunId,
            String aggregateEvidenceFingerprint,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            TestSuiteRunEvidence.PromotionStatus sourcePromotionStatus,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<String> sourcePromotionReasons
    ) {
        /** Normalizes and validates one complete source reference. */
        public SourceSuiteEvidenceRef {
            suiteRunId = normalized(suiteRunId);
            aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
            sourcePromotionReasons = sortedStrings(sourcePromotionReasons);
            if (attempt < 1 || suiteRunId.isBlank()
                    || !fingerprint(aggregateEvidenceFingerprint)
                    || sourcePromotionReasons.size() > 20
                    || sourcePromotionReasons.stream().anyMatch(
                    value -> !REASON_CODE.matcher(value).matches())
                    || sourcePromotionStatus
                    == TestSuiteRunEvidence.PromotionStatus.NOT_EVALUATED
                    || (sourcePromotionStatus == null && !sourcePromotionReasons.isEmpty())
                    || (sourcePromotionStatus == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                    && !sourcePromotionReasons.isEmpty())
                    || (sourcePromotionStatus == TestSuiteRunEvidence.PromotionStatus.BLOCKED
                    && sourcePromotionReasons.isEmpty())) {
                throw new IllegalArgumentException(
                        "Complete stability source evidence identity is required");
            }
        }
    }

    /** Normalizes protocol fields and derives independent verifiability. */
    public TestSuiteStabilityAttestation {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION_V2);
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
        if (!List.of(SCHEMA_VERSION_V1, SCHEMA_VERSION_V2, SCHEMA_VERSION_V3, SCHEMA_VERSION)
                .contains(schemaVersion)) {
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
        String version = attestationVersion(evidence.schemaVersion());
        return new TestSuiteStabilityAttestation(version,
                SignatureStatus.VERIFICATION_UNAVAILABLE,
                evidence.stabilityRunId(), evidence.suiteRef(), requestFingerprint,
                evidenceFingerprint, sources, Instant.EPOCH, "", "", "", false);
    }

    /** @return true only for a complete verified terminal analysis signature */
    public boolean terminallyVerifiable() {
        return independentlyVerifiable;
    }

    private static String attestationVersion(String evidenceVersion) {
        if (TestSuiteStabilityEvidence.SCHEMA_VERSION_V1.equals(evidenceVersion)) {
            return SCHEMA_VERSION_V1;
        }
        if (TestSuiteStabilityEvidence.SCHEMA_VERSION_V2.equals(evidenceVersion)) {
            return SCHEMA_VERSION_V2;
        }
        return TestSuiteStabilityEvidence.SCHEMA_VERSION_V3.equals(evidenceVersion)
                ? SCHEMA_VERSION_V3 : SCHEMA_VERSION;
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

    private static List<String> sortedStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        sorted.removeIf(value -> normalized(value).isBlank());
        sorted.replaceAll(TestSuiteStabilityAttestation::normalized);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
