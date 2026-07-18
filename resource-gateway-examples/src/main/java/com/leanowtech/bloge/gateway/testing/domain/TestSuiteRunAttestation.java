package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domain-separated signature manifest for one suite-run checkpoint or terminal aggregate.
 *
 * <p>The signed material binds the exact suite revision, caller idempotency intent, aggregate
 * evidence fingerprint, ordered child evidence closure, signing time, and attestation scope. A
 * CHECKPOINT signature protects crash recovery from accepting altered progress; only a TERMINAL
 * signature may be consumed as a portable release-gate fact.</p>
 *
 * @param schemaVersion attestation protocol version
 * @param signatureStatus signature availability and verification state
 * @param scope checkpoint or terminal aggregate scope
 * @param suiteRunId durable aggregate run id
 * @param suiteRef exact immutable suite revision
 * @param requestFingerprint canonical suite-execution request fingerprint
 * @param aggregateEvidenceFingerprint canonical aggregate evidence fingerprint
 * @param childEvidenceRefs ordered child evidence closure
 * @param signedAt signing time included in the signed canonical material
 * @param keyId verification-key identifier
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable derived complete-signature claim
 */
public record TestSuiteRunAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        Scope scope,
        String suiteRunId,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String requestFingerprint,
        String aggregateEvidenceFingerprint,
        List<ChildEvidenceRef> childEvidenceRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Historical structural suite-run attestation protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunAttestation.v1";
    /** Semantic suite-run attestation protocol version. */
    public static final String SCHEMA_VERSION_V2 = "bloge.testSuiteRunAttestation.v2";
    /** Schema-admission suite-run attestation protocol version. */
    public static final String SCHEMA_VERSION_V3 = "bloge.testSuiteRunAttestation.v3";
    /** Bounded-property suite-run attestation protocol version. */
    public static final String SCHEMA_VERSION_V4 = "bloge.testSuiteRunAttestation.v4";
    /** Pure-DSL mutation suite-run attestation protocol version. */
    public static final String SCHEMA_VERSION_V5 = "bloge.testSuiteRunAttestation.v5";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Signature state persisted without provider-specific diagnostics. */
    public enum SignatureStatus {
        /** Detached signature was verified before persistence. */
        VERIFIED,
        /** Historical or initial material has no signature. */
        UNSIGNED,
        /** The signing or verification authority could not establish trust. */
        VERIFICATION_UNAVAILABLE
    }

    /** Signed aggregate lifecycle scope. */
    public enum Scope {
        /** Recoverable in-progress state; never a release attestation. */
        CHECKPOINT,
        /** Immutable terminal aggregate suitable for portable verification. */
        TERMINAL
    }

    /**
     * Exact child evidence identity included in suite case order.
     *
     * @param caseId suite-local case id
     * @param runId durable child run id
     * @param evidenceFingerprint complete signed child evidence fingerprint
     */
    public record ChildEvidenceRef(String caseId, String runId, String evidenceFingerprint) {
        /** Normalizes and validates the exact child identity. */
        public ChildEvidenceRef {
            caseId = normalized(caseId);
            runId = normalized(runId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            if (caseId.isBlank() || runId.isBlank() || !fingerprint(evidenceFingerprint)) {
                throw new IllegalArgumentException("Complete child evidence identity is required");
            }
        }
    }

    /** Normalizes protocol fields and derives the independent-verification claim. */
    public TestSuiteRunAttestation {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        signatureStatus = signatureStatus == null ? SignatureStatus.UNSIGNED : signatureStatus;
        scope = scope == null ? Scope.CHECKPOINT : scope;
        suiteRunId = normalized(suiteRunId);
        requestFingerprint = normalized(requestFingerprint);
        aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
        childEvidenceRefs = childEvidenceRefs == null ? List.of() : List.copyOf(childEvidenceRefs);
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        independentlyVerifiable = signatureStatus == SignatureStatus.VERIFIED
                && completeMaterial(suiteRunId, suiteRef, requestFingerprint,
                aggregateEvidenceFingerprint)
                && !keyId.isBlank() && !algorithm.isBlank() && !signature.isBlank()
                && !Instant.EPOCH.equals(signedAt);
        if (!List.of(SCHEMA_VERSION, SCHEMA_VERSION_V2, SCHEMA_VERSION_V3,
                SCHEMA_VERSION_V4, SCHEMA_VERSION_V5)
                .contains(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported suite-run attestation schemaVersion");
        }
        if (SCHEMA_VERSION_V3.equals(schemaVersion) && !childEvidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Schema-admission attestation cannot bind business child evidence");
        }
        if (signatureStatus == SignatureStatus.VERIFIED && !independentlyVerifiable) {
            throw new IllegalArgumentException(
                    "Verified suite-run attestation requires complete signed material");
        }
        if (signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && !completeMaterial(suiteRunId, suiteRef, requestFingerprint,
                aggregateEvidenceFingerprint)) {
            throw new IllegalArgumentException(
                    "Unavailable suite-run attestation requires complete aggregate identity");
        }
    }

    /**
     * Creates the migration marker for an unsigned historical record.
     *
     * @return unsigned attestation without trusted material
     */
    public static TestSuiteRunAttestation unsigned() {
        return new TestSuiteRunAttestation("", SignatureStatus.UNSIGNED, Scope.CHECKPOINT,
                "", null, "", "", List.of(), Instant.EPOCH, "", "", "", false);
    }

    /**
     * Creates a fail-closed manifest when a complete aggregate could not be signed.
     *
     * @param scope checkpoint or terminal scope
     * @param evidence aggregate evidence
     * @param requestFingerprint canonical execution request fingerprint
     * @param aggregateFingerprint canonical aggregate evidence fingerprint
     * @param children ordered verified child closure
     * @return unavailable attestation bound to the supplied aggregate identity
     */
    public static TestSuiteRunAttestation unavailable(
            Scope scope, TestSuiteRunEvidenceProtocol evidence, String requestFingerprint,
            String aggregateFingerprint, List<ChildEvidenceRef> children) {
        if (evidence == null) {
            throw new IllegalArgumentException("Aggregate evidence is required");
        }
        String version = evidence instanceof TestSuiteRunEvidenceV5
                ? SCHEMA_VERSION_V5
                : evidence instanceof TestSuiteRunEvidenceV4
                ? SCHEMA_VERSION_V4
                : evidence instanceof TestSuiteRunEvidenceV3 ? SCHEMA_VERSION_V3
                : evidence instanceof TestSuiteRunEvidenceV2 ? SCHEMA_VERSION_V2 : SCHEMA_VERSION;
        return new TestSuiteRunAttestation(version, SignatureStatus.VERIFICATION_UNAVAILABLE,
                scope, evidence.suiteRunId(), evidence.suiteRef(), requestFingerprint,
                aggregateFingerprint, children, Instant.EPOCH, "", "", "", false);
    }

    /**
     * Indicates whether this is a cryptographically verified terminal aggregate.
     *
     * @return true only for an independently verifiable terminal attestation
     */
    public boolean terminallyVerifiable() {
        return independentlyVerifiable && scope == Scope.TERMINAL;
    }

    private static boolean completeMaterial(String suiteRunId,
                                            TestSuiteExecutionRequest.SuiteRef suiteRef,
                                            String requestFingerprint,
                                            String aggregateFingerprint) {
        return !normalized(suiteRunId).isBlank() && suiteRef != null
                && !normalized(suiteRef.suiteId()).isBlank() && suiteRef.revision() > 0
                && fingerprint(suiteRef.fingerprint()) && fingerprint(requestFingerprint)
                && fingerprint(aggregateFingerprint);
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
