package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed detached signature manifest for a suite checkpoint or terminal evidence closure.
 *
 * @param schemaVersion attestation wire version
 * @param signatureStatus persisted signature state
 * @param scope checkpoint or terminal signature domain
 * @param suiteRunId durable aggregate run id
 * @param suiteRef exact immutable suite identity
 * @param requestFingerprint canonical execution request fingerprint
 * @param aggregateEvidenceFingerprint canonical aggregate evidence fingerprint
 * @param childEvidenceRefs ordered child evidence closure in suite case order
 * @param signedAt time included in signed material
 * @param keyId verification key id
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable producer completeness claim
 */
public record TestSuiteRunAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        Scope scope,
        String suiteRunId,
        SuiteRef suiteRef,
        String requestFingerprint,
        String aggregateEvidenceFingerprint,
        List<ChildEvidenceRef> childEvidenceRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Persisted signature state. */
    public enum SignatureStatus {
        /** Producer signed and immediately verified the material. */
        VERIFIED,
        /** Historical material has no signature. */
        UNSIGNED,
        /** Producer could not establish signing or verification trust. */
        VERIFICATION_UNAVAILABLE
    }

    /** Signed aggregate lifecycle domain. */
    public enum Scope {
        /** Recoverable in-progress state. */
        CHECKPOINT,
        /** Immutable terminal aggregate. */
        TERMINAL
    }

    /**
     * Exact immutable suite identity.
     *
     * @param suiteId stable suite id
     * @param revision positive immutable revision
     * @param fingerprint suite content fingerprint
     */
    public record SuiteRef(String suiteId, long revision, String fingerprint) {
        /** Normalizes and validates the immutable suite identity. */
        public SuiteRef {
            suiteId = normalized(suiteId);
            fingerprint = normalized(fingerprint);
            if (suiteId.isBlank() || revision < 1
                    || !TestSuiteRunAttestation.fingerprint(fingerprint)) {
                throw new IllegalArgumentException("Suite attestation reference is incomplete");
            }
        }
    }

    /**
     * Exact child evidence identity in suite case order.
     *
     * @param caseId suite-local case id
     * @param runId durable child run id
     * @param evidenceFingerprint complete child evidence fingerprint
     */
    public record ChildEvidenceRef(String caseId, String runId, String evidenceFingerprint) {
        /** Normalizes and validates child evidence identity. */
        public ChildEvidenceRef {
            caseId = normalized(caseId);
            runId = normalized(runId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            if (caseId.isBlank() || runId.isBlank() || !fingerprint(evidenceFingerprint)) {
                throw new IllegalArgumentException("Suite child evidence reference is incomplete");
            }
        }
    }

    /** Normalizes values and rejects impossible verified signature claims. */
    public TestSuiteRunAttestation {
        schemaVersion = normalized(schemaVersion);
        suiteRunId = normalized(suiteRunId);
        requestFingerprint = normalized(requestFingerprint);
        aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
        childEvidenceRefs = childEvidenceRefs == null ? List.of() : List.copyOf(childEvidenceRefs);
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        if (signatureStatus == null || scope == null) {
            throw new IllegalArgumentException("Suite attestation state and scope are required");
        }
        if (signatureStatus == SignatureStatus.VERIFIED
                && (!List.of(TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V1,
                        TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V2,
                        TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V3,
                        TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V4).contains(schemaVersion)
                || suiteRunId.isBlank() || suiteRef == null || !fingerprint(requestFingerprint)
                || !fingerprint(aggregateEvidenceFingerprint) || Instant.EPOCH.equals(signedAt)
                || keyId.isBlank() || algorithm.isBlank() || signature.isBlank()
                || !independentlyVerifiable)) {
            throw new IllegalArgumentException("Verified suite attestation is incomplete");
        }
        if (TestingProtocol.TEST_SUITE_RUN_ATTESTATION_V3.equals(schemaVersion)
                && !childEvidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Schema-admission attestation cannot reference business child evidence");
        }
    }

    /**
     * Decodes a schema-validated suite-run attestation.
     *
     * @param value attestation JSON object
     * @return typed immutable attestation
     */
    public static TestSuiteRunAttestation from(JsonNode value) {
        if (value == null || !value.isObject()) {
            return unsigned();
        }
        SignatureStatus status = enumValue(SignatureStatus.class,
                value.path("signatureStatus").asText(), "signature status");
        Scope scope = enumValue(Scope.class, value.path("scope").asText(), "scope");
        JsonNode suite = value.path("suiteRef");
        SuiteRef suiteRef = suite.isObject() ? new SuiteRef(suite.path("suiteId").asText(),
                suite.path("revision").asLong(), suite.path("fingerprint").asText()) : null;
        List<ChildEvidenceRef> children = new ArrayList<>();
        value.path("childEvidenceRefs").forEach(child -> children.add(new ChildEvidenceRef(
                child.path("caseId").asText(), child.path("runId").asText(),
                child.path("evidenceFingerprint").asText())));
        return new TestSuiteRunAttestation(value.path("schemaVersion").asText(), status, scope,
                value.path("suiteRunId").asText(), suiteRef,
                value.path("requestFingerprint").asText(),
                value.path("aggregateEvidenceFingerprint").asText(), children,
                instant(value.path("signedAt").asText()), value.path("keyId").asText(),
                value.path("algorithm").asText(), value.path("signature").asText(),
                value.path("independentlyVerifiable").asBoolean(false));
    }

    /**
     * Creates the explicit migration marker for a v1 suite response.
     *
     * @return unsigned, non-verifiable marker
     */
    public static TestSuiteRunAttestation unsigned() {
        return new TestSuiteRunAttestation("", SignatureStatus.UNSIGNED, Scope.CHECKPOINT,
                "", null, "", "", List.of(), Instant.EPOCH, "", "", "", false);
    }

    /**
     * Indicates whether this value can be submitted to terminal offline verification.
     *
     * @return true only for a complete producer-verified terminal claim
     */
    public boolean terminallyVerifiable() {
        return signatureStatus == SignatureStatus.VERIFIED && scope == Scope.TERMINAL
                && independentlyVerifiable;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(normalized(value));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Suite attestation signedAt is invalid");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown suite attestation " + field);
        }
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
