package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-light independent verifier for one signed read-only Shadow comparison.
 *
 * <p>The verifier links no Resource Gateway server implementation class. It validates the strict
 * packaged Schema, proves exact request pairing, zero-write access, and v3 double-observed online
 * authority closure, derives each typed match outcome from normalized fact fingerprints,
 * recomputes the content address, enforces key lifecycle policy, and verifies the
 * domain-separated Ed25519 seal. The bounded result contains no request, response, or business
 * payload.</p>
 */
public final class ReadOnlyShadowComparisonVerifier {
    /** Maximum canonical comparison bytes admitted to hashing. */
    public static final int MAXIMUM_COMPARISON_BYTES =
            2 * 1024 * 1024;
    /** Maximum domain-separated attestation material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);
    private static final Map<String, Set<String>>
            DIMENSION_DIFF_TYPES =
            Map.of(
                    "BEHAVIOR",
                    Set.of(
                            "BRANCH",
                            "ERROR_CODE",
                            "FALLBACK",
                            "OUTPUT_VALUE",
                            "RETRY",
                            "TERMINAL_STATUS"),
                    "CONTRACT",
                    Set.of(
                            "OUTPUT_SCHEMA",
                            "UNKNOWN_FIELD"),
                    "EFFECT",
                    Set.of("EFFECT"),
                    "STATE_TRANSITION",
                    Set.of("STATE"));

    /** Creates a dependency-light verifier using packaged Schemas and a caller-supplied key. */
    public ReadOnlyShadowComparisonVerifier() {
    }

    /** Bounded verification outcome. */
    public enum Outcome {
        /** Schema, closure, derivation, content address, key policy, and signature all passed. */
        VERIFIED,
        /** Structure, semantic closure, content address, or signature is invalid. */
        INVALID,
        /** The exact comparison verification key is unavailable. */
        KEY_UNAVAILABLE,
        /** Signature algorithm or key lifecycle policy rejects the comparison. */
        POLICY_REJECTED
    }

    /**
     * Payload-free result safe for CI and governance logs.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param comparisonId comparison identity, or blank when unavailable
     * @param comparisonFingerprint content address, or blank when unavailable
     * @param unitId owner inventory unit, or blank when unavailable
     * @param keyId verification key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String comparisonId,
            String comparisonFingerprint,
            String unitId,
            String keyId
    ) {
        /** Normalizes one bounded log-safe result. */
        public VerificationResult {
            reasonCode = normalized(reasonCode, 255);
            comparisonId = normalized(
                    comparisonId, 512);
            comparisonFingerprint = normalized(
                    comparisonFingerprint, 128);
            unitId = normalized(unitId, 512);
            keyId = normalized(keyId, 255);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Shadow comparison verification result is invalid");
            }
        }

        /**
         * Reports whether every independent comparison verification step passed.
         *
         * @return true only when all independent checks pass
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded comparison and its detached signature.
     *
     * @param comparison decoded comparison
     * @param key public key resolved by {@code comparisonSeal.keyId}; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode comparison,
            EvidenceVerificationKey key) {
        Coordinates coordinates =
                Coordinates.from(comparison);
        String schemaVersion =
                comparison == null
                        ? ""
                        : comparison.path(
                        "schemaVersion").asText("");
        String schemaResource = switch (schemaVersion) {
            case CapabilityMirrorProtocol
                    .READ_ONLY_SHADOW_COMPARISON_V1 ->
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE;
            case CapabilityMirrorProtocol
                    .READ_ONLY_SHADOW_COMPARISON_V2 ->
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_COMPARISON_V2_SCHEMA_RESOURCE;
            case CapabilityMirrorProtocol
                    .READ_ONLY_SHADOW_COMPARISON_V3 ->
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_COMPARISON_V3_SCHEMA_RESOURCE;
            default -> "";
        };
        try {
            CapabilityMirrorSchemaValidator.require(
                    comparison,
                    schemaResource,
                    "RG.MIRROR.CLIENT.SHADOW_COMPARISON_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_COMPARISON_SCHEMA_INVALID",
                    coordinates);
        }
        Instant observedAt;
        try {
            observedAt = verifySemantics(comparison);
            verifyFingerprint(comparison);
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_COMPARISON_CLOSURE_INVALID",
                    coordinates);
        }

        JsonNode seal = comparison.path(
                "comparisonSeal");
        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "SHADOW_COMPARISON_VERIFICATION_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!key.keyId().equals(text(seal, "keyId"))) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_COMPARISON_KEY_ID_MISMATCH",
                    coordinates);
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                text(seal, "algorithm"))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SHADOW_COMPARISON_SIGNATURE_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = instant(
                    seal.path("signedAt"),
                    "SHADOW_COMPARISON_SEAL_TIME_INVALID");
        } catch (VerificationFailure invalid) {
            return result(
                    Outcome.INVALID,
                    invalid.reasonCode,
                    coordinates);
        }
        if (!key.verificationAllowed()
                || signedAt.isBefore(
                observedAt.minus(MAXIMUM_CLOCK_SKEW))
                || signedAt.isBefore(
                key.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SHADOW_COMPARISON_KEY_POLICY_REJECTED",
                    coordinates);
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            attestationMaterial(comparison),
                            MAXIMUM_ATTESTATION_BYTES);
            if (!materialFingerprint.equals(
                    text(seal, "materialFingerprint"))) {
                return result(
                        Outcome.INVALID,
                        "SHADOW_COMPARISON_ATTESTATION_MATERIAL_INVALID",
                        coordinates);
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint,
                    text(seal, "signature"),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "SHADOW_COMPARISON_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates);
        } catch (GeneralSecurityException
                 | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SHADOW_COMPARISON_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static Instant verifySemantics(
            JsonNode comparison) {
        JsonNode scope = comparison.path("scope");
        JsonNode baseline = comparison.path("baseline");
        JsonNode candidate = comparison.path("candidate");
        if (!scope.equals(baseline.path("scope"))
                || !scope.equals(candidate.path("scope"))
                || !text(baseline, "requestContextFingerprint")
                .equals(text(
                        candidate,
                        "requestContextFingerprint"))
                || !comparison.path("targetCapabilityRef")
                .equals(candidate.path(
                        "targetCapabilityRef"))) {
            fail("SHADOW_COMPARISON_SOURCE_PAIR_INVALID");
        }
        JsonNode access = comparison.path("accessProof");
        long sampleOrdinal =
                access.path("sampleOrdinal").asLong();
        long maximumSamples =
                access.path("maximumSamples").asLong();
        if (sampleOrdinal < 1
                || maximumSamples < 1
                || sampleOrdinal > maximumSamples
                || access.path(
                "writeCredentialExposed").asBoolean()
                || access.path(
                "writeAttemptCount").asLong() != 0) {
            fail("SHADOW_COMPARISON_ZERO_WRITE_PROOF_INVALID");
        }
        Instant observedAt = instant(
                comparison.path("observedAt"),
                "SHADOW_COMPARISON_TIME_INVALID");
        if (CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V3
                .equals(text(
                        comparison, "schemaVersion"))) {
            JsonNode authority =
                    comparison.path("authorityProof");
            Instant admittedAt = instant(
                    authority.path("admittedAt"),
                    "SHADOW_COMPARISON_AUTHORITY_TIME_INVALID");
            Instant confirmedAt = instant(
                    authority.path("confirmedAt"),
                    "SHADOW_COMPARISON_AUTHORITY_TIME_INVALID");
            if (confirmedAt.isBefore(admittedAt)
                    || observedAt.isBefore(confirmedAt)) {
                fail("SHADOW_COMPARISON_AUTHORITY_TIME_INVALID");
            }
            if (!sameCoordinates(
                    access.path("samplingGrantRef"),
                    authority.path(
                            "samplingGrantAttestationRef"))
                    || !sameCoordinates(
                    access.path("killSwitchRef"),
                    authority.path(
                            "killSwitchAttestationRef"))
                    || !sameCoordinates(
                    authority.path("guardPolicyRef"),
                    authority.path(
                            "guardPolicyAttestationRef"))) {
                fail("SHADOW_COMPARISON_AUTHORITY_CLOSURE_INVALID");
            }
        }
        if (observedAt.isBefore(
                instant(
                        baseline.path("completedAt"),
                        "SHADOW_COMPARISON_TIME_INVALID"))
                || observedAt.isBefore(
                instant(
                        candidate.path("completedAt"),
                        "SHADOW_COMPARISON_TIME_INVALID"))) {
            fail("SHADOW_COMPARISON_TIME_INVALID");
        }
        String previousDimension = "";
        Set<String> dimensions = new HashSet<>();
        for (JsonNode result : comparison.path(
                "results")) {
            String dimension = text(
                    result, "dimension");
            if (!dimensions.add(dimension)
                    || dimension.compareTo(
                    previousDimension) <= 0) {
                fail("SHADOW_COMPARISON_RESULT_ORDER_INVALID");
            }
            previousDimension = dimension;
            verifyResult(result, dimension);
        }
        return observedAt;
    }

    private static boolean sameCoordinates(
            JsonNode material,
            JsonNode attestation) {
        return text(material, "id").equals(
                text(attestation, "id"))
                && material.path("revision").asLong()
                == attestation.path("revision").asLong();
    }

    private static void verifyResult(
            JsonNode result, String dimension) {
        String baseline = text(
                result, "baselineFingerprint");
        String candidate = text(
                result, "candidateFingerprint");
        String outcome = text(result, "outcome");
        List<String> diffTypes =
                arrayText(result.path("diffTypes"));
        if (!canonical(diffTypes)
                || diffTypes.stream().anyMatch(
                type -> !"EVIDENCE_GAP".equals(type)
                        && !DIMENSION_DIFF_TYPES
                        .getOrDefault(
                                dimension, Set.of())
                        .contains(type))) {
            fail("SHADOW_COMPARISON_DIFF_TYPE_INVALID");
        }
        boolean baselinePresent = !baseline.isBlank();
        boolean candidatePresent = !candidate.isBlank();
        if ("MATCH".equals(outcome)
                && (!baselinePresent
                || !candidatePresent
                || !baseline.equals(candidate)
                || !diffTypes.isEmpty())
                || "MISMATCH".equals(outcome)
                && (!baselinePresent
                || !candidatePresent
                || baseline.equals(candidate)
                || diffTypes.isEmpty()
                || diffTypes.contains(
                "EVIDENCE_GAP"))
                || "INDETERMINATE".equals(outcome)
                && (baselinePresent
                && candidatePresent
                || !diffTypes.equals(
                List.of("EVIDENCE_GAP")))) {
            fail("SHADOW_COMPARISON_RESULT_DERIVATION_INVALID");
        }
    }

    private static void verifyFingerprint(
            JsonNode comparison) {
        ObjectNode material =
                comparison.deepCopy();
        material.put("comparisonFingerprint", "");
        material.set(
                "comparisonSeal", unsignedSeal());
        if (!text(
                comparison,
                "comparisonFingerprint").equals(
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        MAXIMUM_COMPARISON_BYTES))) {
            fail("SHADOW_COMPARISON_FINGERPRINT_INVALID");
        }
    }

    private static ObjectNode attestationMaterial(
            JsonNode comparison) {
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        String version = text(
                comparison, "schemaVersion");
        String domain = switch (version) {
            case CapabilityMirrorProtocol
                    .READ_ONLY_SHADOW_COMPARISON_V1 ->
                    "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V1";
            case CapabilityMirrorProtocol
                    .READ_ONLY_SHADOW_COMPARISON_V2 ->
                    "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V2";
            default ->
                    "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V3";
        };
        material.put("domain", domain);
        for (String field : List.of(
                "schemaVersion",
                "comparisonId",
                "revision",
                "inventoryRef",
                "unitId",
                "observedAt",
                "comparisonFingerprint")) {
            material.set(
                    field,
                    comparison.path(field).deepCopy());
        }
        return material;
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode seal =
                JsonNodeFactory.instance.objectNode();
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", "");
        seal.put("algorithm", "");
        seal.put("keyId", "");
        seal.put(
                "signedAt",
                Instant.EPOCH.toString());
        seal.put("signature", "");
        return seal;
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.comparisonId,
                coordinates.comparisonFingerprint,
                coordinates.unitId,
                coordinates.keyId);
    }

    private static String text(
            JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static Instant instant(
            JsonNode value, String reasonCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            fail(reasonCode);
            throw new IllegalStateException("unreachable");
        }
    }

    private static List<String> arrayText(
            JsonNode values) {
        java.util.ArrayList<String> result =
                new java.util.ArrayList<>();
        values.forEach(
                value -> result.add(
                        value.asText("")));
        return List.copyOf(result);
    }

    private static boolean canonical(
            List<String> values) {
        return values.equals(
                values.stream()
                        .distinct()
                        .sorted()
                        .toList());
    }

    private static String normalized(
            String value, int maximum) {
        String result = value == null
                ? "" : value.trim();
        return result.length() <= maximum
                ? result
                : result.substring(0, maximum);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private record Coordinates(
            String comparisonId,
            String comparisonFingerprint,
            String unitId,
            String keyId
    ) {
        private static Coordinates from(
                JsonNode comparison) {
            JsonNode source = comparison == null
                    ? JsonNodeFactory.instance
                    .objectNode()
                    : comparison;
            return new Coordinates(
                    text(source, "comparisonId"),
                    text(
                            source,
                            "comparisonFingerprint"),
                    text(source, "unitId"),
                    text(
                            source.path("comparisonSeal"),
                            "keyId"));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }
}
