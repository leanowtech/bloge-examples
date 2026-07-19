package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed detached signature manifest for one terminal suite-stability analysis.
 *
 * @param schemaVersion exact stability attestation wire version
 * @param signatureStatus producer signature state
 * @param stabilityRunId deterministic stability analysis id
 * @param suiteRef exact immutable suite identity
 * @param requestFingerprint canonical parent request fingerprint
 * @param evidenceFingerprint canonical stability evidence fingerprint
 * @param sourceSuiteEvidenceRefs ordered source suite-run closure
 * @param signedAt time included in signed material
 * @param keyId verification key id
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable producer completeness claim
 */
public record TestSuiteStabilityAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String stabilityRunId,
        SuiteRef suiteRef,
        String requestFingerprint,
        String evidenceFingerprint,
        List<SourceSuiteEvidenceRef> sourceSuiteEvidenceRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Persisted producer signature state. */
    public enum SignatureStatus {
        /** Producer signed and immediately verified the complete material. */
        VERIFIED,
        /** Material has no detached signature. */
        UNSIGNED,
        /** Producer could not establish signing or verification trust. */
        VERIFICATION_UNAVAILABLE
    }

    /**
     * Exact immutable suite identity.
     *
     * @param suiteId stable suite id
     * @param revision positive immutable revision
     * @param fingerprint suite content fingerprint
     */
    public record SuiteRef(String suiteId, long revision, String fingerprint) {
        /** Normalizes and validates one exact suite identity. */
        public SuiteRef {
            suiteId = normalized(suiteId);
            fingerprint = normalized(fingerprint);
            if (suiteId.isBlank() || revision < 1
                    || !TestSuiteStabilityAttestation.fingerprint(fingerprint)) {
                throw new IllegalArgumentException("Stability suite reference is incomplete");
            }
        }
    }

    /**
     * Exact source suite evidence identity bound to one rerun coordinate.
     *
     * @param attempt one-based rerun coordinate
     * @param suiteRunId durable source suite-run id
     * @param aggregateEvidenceFingerprint source aggregate evidence fingerprint
     * @param sourcePromotionStatus exact source suite promotion status
     * @param sourcePromotionReasons bounded source suite promotion reasons
     */
    public record SourceSuiteEvidenceRef(
            int attempt,
            String suiteRunId,
            String aggregateEvidenceFingerprint,
            TestSuiteStabilityRun.SourcePromotionStatus sourcePromotionStatus,
            List<String> sourcePromotionReasons
    ) {
        /** Normalizes and validates one complete source reference. */
        public SourceSuiteEvidenceRef {
            suiteRunId = normalized(suiteRunId);
            aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
            sourcePromotionReasons = sourcePromotionReasons == null
                    ? List.of() : sourcePromotionReasons.stream().map(
                    TestSuiteStabilityAttestation::machineCode).distinct().sorted().toList();
            if (attempt < 1 || attempt > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                    || suiteRunId.isBlank()
                    || !fingerprint(aggregateEvidenceFingerprint)
                    || sourcePromotionReasons.size() > 20
                    || (sourcePromotionStatus == null && !sourcePromotionReasons.isEmpty())
                    || (sourcePromotionStatus
                    == TestSuiteStabilityRun.SourcePromotionStatus.ELIGIBLE
                    && !sourcePromotionReasons.isEmpty())
                    || (sourcePromotionStatus
                    == TestSuiteStabilityRun.SourcePromotionStatus.BLOCKED
                    && sourcePromotionReasons.isEmpty())) {
                throw new IllegalArgumentException(
                        "Stability source evidence reference is incomplete");
            }
        }
    }

    /** Normalizes the manifest and rejects impossible verified claims. */
    public TestSuiteStabilityAttestation {
        schemaVersion = normalized(schemaVersion);
        stabilityRunId = normalized(stabilityRunId);
        requestFingerprint = normalized(requestFingerprint);
        evidenceFingerprint = normalized(evidenceFingerprint);
        sourceSuiteEvidenceRefs = sourceSuiteEvidenceRefs == null
                ? List.of() : List.copyOf(sourceSuiteEvidenceRefs);
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        if (signatureStatus == null) {
            throw new IllegalArgumentException("Stability signature status is required");
        }
        if (signatureStatus == SignatureStatus.VERIFIED
                && (!List.of(TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V1,
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V2,
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V3,
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V4,
                TestingProtocol.TEST_SUITE_STABILITY_ATTESTATION_V5).contains(schemaVersion)
                || !stabilityRunId(stabilityRunId) || suiteRef == null
                || !fingerprint(requestFingerprint) || !fingerprint(evidenceFingerprint)
                || Instant.EPOCH.equals(signedAt) || keyId.isBlank() || algorithm.isBlank()
                || signature.isBlank() || !independentlyVerifiable)) {
            throw new IllegalArgumentException("Verified stability attestation is incomplete");
        }
    }

    /**
     * Decodes one schema-validated stability attestation.
     *
     * @param value exact attestation JSON object
     * @return typed immutable manifest
     */
    public static TestSuiteStabilityAttestation from(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Stability attestation is absent");
        }
        JsonNode suite = value.path("suiteRef");
        SuiteRef suiteRef = suite.isObject() ? new SuiteRef(suite.path("suiteId").asText(),
                suite.path("revision").asLong(), suite.path("fingerprint").asText()) : null;
        List<SourceSuiteEvidenceRef> sources = new ArrayList<>();
        value.path("sourceSuiteEvidenceRefs").forEach(source ->
                sources.add(new SourceSuiteEvidenceRef(source.path("attempt").asInt(),
                        source.path("suiteRunId").asText(),
                        source.path("aggregateEvidenceFingerprint").asText(),
                        nullableEnum(TestSuiteStabilityRun.SourcePromotionStatus.class,
                                source.path("sourcePromotionStatus")),
                        strings(source.path("sourcePromotionReasons")))));
        return new TestSuiteStabilityAttestation(value.path("schemaVersion").asText(),
                enumValue(SignatureStatus.class, value.path("signatureStatus").asText()),
                value.path("stabilityRunId").asText(), suiteRef,
                value.path("requestFingerprint").asText(),
                value.path("evidenceFingerprint").asText(), sources,
                instant(value.path("signedAt").asText()), value.path("keyId").asText(),
                value.path("algorithm").asText(), value.path("signature").asText(),
                value.path("independentlyVerifiable").asBoolean(false));
    }

    /**
     * Indicates whether this manifest can enter offline terminal verification.
     *
     * @return true only for a complete producer-verified claim
     */
    public boolean terminallyVerifiable() {
        return signatureStatus == SignatureStatus.VERIFIED && independentlyVerifiable;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(normalized(value));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Stability attestation signedAt is invalid");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown stability attestation state");
        }
    }

    private static <E extends Enum<E>> E nullableEnum(Class<E> type, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return enumValue(type, value.asText());
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static String machineCode(String value) {
        String safe = normalized(value);
        if (!safe.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Source promotion reason is invalid");
        }
        return safe;
    }

    private static boolean stabilityRunId(String value) {
        return normalized(value).matches("stability-[0-9a-f]{64}");
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
