package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Dependency-light offline verifier for a mirror deployment-isolation attestation.
 *
 * <p>The verifier validates the packaged strict schema, deterministic collection order, bounded
 * validity and issuance windows, exact locally supplied deployment identity, content-addressed
 * fingerprints, authority lifecycle policy, and the detached Ed25519 signature. Producer labels
 * and evidence signatures never substitute for the separate isolation authority key.</p>
 */
public final class MirrorDeploymentIsolationAttestationVerifier {
    /** Maximum canonical signed material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 512 * 1024;
    /** Maximum canonical complete artifact size. */
    public static final int MAXIMUM_ATTESTATION_BYTES = 1024 * 1024;
    /** Maximum v1 isolation observation lifetime. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
    /** Maximum observation-to-signature delay. */
    public static final Duration MAXIMUM_ISSUANCE_DELAY = Duration.ofMinutes(5);
    /** Signature domain shared with external issuers. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_DEPLOYMENT_ISOLATION_V1";

    private static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAttestation.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates a stateless offline verifier. */
    public MirrorDeploymentIsolationAttestationVerifier() {
    }

    /** Bounded verification outcome. */
    public enum Outcome {
        /** Every structural, cryptographic, identity, policy, and time check passed. */
        VERIFIED,
        /** Structure, canonical material, or signature is invalid. */
        INVALID,
        /** No exact isolation authority key was supplied. */
        KEY_UNAVAILABLE,
        /** Authority identity, lifecycle, or algorithm policy rejected the artifact. */
        POLICY_REJECTED,
        /** Local deployment coordinates differ from the attested generation. */
        IDENTITY_MISMATCH,
        /** The execution does not fit wholly within the signed validity window. */
        WINDOW_REJECTED
    }

    /**
     * Payload-free verification result suitable for CI and correctness workbooks.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param attestationId artifact identity, or blank when unavailable
     * @param attestationFingerprint artifact fingerprint, or blank when unavailable
     * @param keyId isolation-authority key id, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String attestationId,
            String attestationFingerprint,
            String keyId
    ) {
        /** Validates log-safe result coordinates. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            attestationId = normalized(attestationId);
            attestationFingerprint = normalized(attestationFingerprint);
            keyId = normalized(keyId);
            if (outcome == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "deployment isolation verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only when every independent verification step passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded attestation against local immutable coordinates.
     *
     * @param attestation decoded strict attestation value
     * @param key externally pinned authority key; may be {@code null}
     * @param expectedDeployment immutable local runtime identity
     * @param executionStartedAt actual mirror execution start
     * @param executionCompletedAt actual mirror execution completion
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode attestation,
            MirrorDeploymentIsolationVerificationKey key,
            MirrorDeploymentIdentity expectedDeployment,
            Instant executionStartedAt,
            Instant executionCompletedAt) {
        Coordinates coordinates = Coordinates.from(attestation);
        try {
            CapabilityMirrorSchemaValidator.require(attestation,
                    CapabilityMirrorProtocol
                            .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.DEPLOYMENT_ISOLATION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "ATTESTATION_SCHEMA_INVALID", coordinates);
        }
        try {
            verifyDeterministicOrder(attestation);
            verifyProtocolWindows(attestation);
            verifyCanonicalBase64(attestation.at("/seal/signature").asText());
            String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    signatureMaterial(attestation), MAXIMUM_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    attestation.at("/seal/materialFingerprint").asText())) {
                return result(Outcome.INVALID,
                        "ATTESTATION_MATERIAL_FINGERPRINT_INVALID", coordinates);
            }
            String artifactFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    artifactMaterial(attestation), MAXIMUM_ATTESTATION_BYTES);
            if (!artifactFingerprint.equals(
                    attestation.path("attestationFingerprint").asText())) {
                return result(Outcome.INVALID,
                        "ATTESTATION_FINGERPRINT_INVALID", coordinates);
            }
        } catch (VerificationFailure failure) {
            return result(Outcome.INVALID, failure.reasonCode, coordinates);
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "ATTESTATION_MATERIAL_INVALID", coordinates);
        }
        if (key == null) {
            return result(Outcome.KEY_UNAVAILABLE, "AUTHORITY_KEY_UNAVAILABLE", coordinates);
        }
        JsonNode material = attestation.path("material");
        JsonNode seal = attestation.path("seal");
        if (!key.keyId().equals(seal.path("keyId").asText())
                || !key.issuer().equals(material.path("issuer").asText())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_IDENTITY_MISMATCH", coordinates);
        }
        if (!key.verificationAllowed() || !"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(seal.path("algorithm").asText())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_POLICY_REJECTED", coordinates);
        }
        Instant signedAt = instant(seal.path("signedAt"));
        if (signedAt == null) {
            return result(Outcome.INVALID, "ATTESTATION_TIME_INVALID", coordinates);
        }
        if (signedAt.isBefore(key.notBefore()) || !signedAt.isBefore(key.notAfter())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_OUTSIDE_VALIDITY", coordinates);
        }
        if (expectedDeployment == null
                || !deploymentMatches(material.path("deployment"), expectedDeployment)) {
            return result(Outcome.IDENTITY_MISMATCH,
                    "DEPLOYMENT_IDENTITY_MISMATCH", coordinates);
        }
        if (!validExecutionWindow(attestation, executionStartedAt, executionCompletedAt)) {
            return result(Outcome.WINDOW_REJECTED,
                    "EXECUTION_OUTSIDE_ATTESTATION_WINDOW", coordinates);
        }
        try {
            if (!EvidenceVerificationSupport.verifyEd25519(
                    seal.path("materialFingerprint").asText(),
                    seal.path("signature").asText(), key.encodedPublicKey())) {
                return result(Outcome.INVALID, "ATTESTATION_SIGNATURE_INVALID", coordinates);
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(Outcome.INVALID,
                    "ATTESTATION_SIGNATURE_MATERIAL_INVALID", coordinates);
        }
    }

    private static void verifyProtocolWindows(JsonNode attestation) {
        JsonNode material = attestation.path("material");
        Instant observed = requiredInstant(material.path("observedAt"));
        Instant validFrom = requiredInstant(material.path("validFrom"));
        Instant expires = requiredInstant(material.path("expiresAt"));
        Instant signedAt = requiredInstant(attestation.at("/seal/signedAt"));
        if (validFrom.isBefore(observed) || !expires.isAfter(validFrom)
                || Duration.between(observed, expires).compareTo(MAXIMUM_LIFETIME) > 0
                || signedAt.isBefore(observed) || !signedAt.isBefore(expires)
                || Duration.between(observed, signedAt)
                .compareTo(MAXIMUM_ISSUANCE_DELAY) > 0) {
            fail("ATTESTATION_WINDOW_INVALID");
        }
    }

    private static boolean validExecutionWindow(
            JsonNode attestation, Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            return false;
        }
        Instant validFrom = requiredInstant(attestation.at("/material/validFrom"));
        Instant signedAt = requiredInstant(attestation.at("/seal/signedAt"));
        Instant effectiveFrom = validFrom.isAfter(signedAt) ? validFrom : signedAt;
        Instant expiresAt = requiredInstant(attestation.at("/material/expiresAt"));
        return !startedAt.isBefore(effectiveFrom) && completedAt.isBefore(expiresAt);
    }

    private static boolean deploymentMatches(
            JsonNode actual, MirrorDeploymentIdentity expected) {
        return expected.deploymentScopeId().equals(actual.path("deploymentScopeId").asText())
                && expected.clusterId().equals(actual.path("clusterId").asText())
                && expected.namespace().equals(actual.path("namespace").asText())
                && expected.workloadName().equals(actual.path("workloadName").asText())
                && expected.serviceAccount().equals(actual.path("serviceAccount").asText())
                && expected.imageDigest().equals(actual.path("imageDigest").asText());
    }

    private static void verifyDeterministicOrder(JsonNode attestation) {
        JsonNode enforcement = attestation.at("/material/enforcement");
        requireOrder(enforcement.path("enforcementLayers"), Comparator.comparing(JsonNode::asText),
                JsonNode::asText, "ATTESTATION_ENFORCEMENT_ORDER_INVALID");
        requireOrder(enforcement.path("allowedEgressClasses"),
                Comparator.comparing(JsonNode::asText), JsonNode::asText,
                "ATTESTATION_EGRESS_CLASS_ORDER_INVALID");
        requireOrder(enforcement.path("proofRefs"),
                Comparator.comparing((JsonNode value) -> value.path("kind").asText())
                        .thenComparing(value -> value.path("id").asText())
                        .thenComparingLong(value -> value.path("revision").asLong())
                        .thenComparing(value -> value.path("fingerprint").asText()),
                value -> value.path("kind").asText() + '\0' + value.path("id").asText()
                        + '\0' + value.path("revision").asLong(),
                "ATTESTATION_PROOF_ORDER_INVALID");
    }

    private static void requireOrder(
            JsonNode values,
            Comparator<JsonNode> comparator,
            java.util.function.Function<JsonNode, String> coordinate,
            String reason) {
        JsonNode previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode value : values) {
            if (previous != null && comparator.compare(previous, value) > 0
                    || !seen.add(coordinate.apply(value))) {
                fail(reason);
            }
            previous = value;
        }
    }

    private static ObjectNode signatureMaterial(JsonNode attestation) {
        ObjectNode material = JSON.createObjectNode();
        material.put("domain", SIGNATURE_DOMAIN);
        material.put("schemaVersion", SCHEMA_VERSION);
        material.set("material", attestation.path("material").deepCopy());
        return material;
    }

    private static JsonNode artifactMaterial(JsonNode attestation) {
        ObjectNode material = ((ObjectNode) attestation).deepCopy();
        material.put("attestationFingerprint", "");
        return material;
    }

    private static Instant requiredInstant(JsonNode value) {
        String encoded = value.asText();
        Instant exact = instant(value);
        if (exact == null || Instant.EPOCH.equals(exact) || !exact.toString().equals(encoded)) {
            fail("ATTESTATION_TIME_INVALID");
        }
        return exact;
    }

    private static void verifyCanonicalBase64(String value) {
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(value);
            if (decoded.length == 0 || !value.equals(
                    java.util.Base64.getEncoder().encodeToString(decoded))) {
                fail("ATTESTATION_SIGNATURE_ENCODING_INVALID");
            }
        } catch (IllegalArgumentException invalid) {
            fail("ATTESTATION_SIGNATURE_ENCODING_INVALID");
        }
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates) {
        return new VerificationResult(outcome, reason, coordinates.attestationId(),
                coordinates.attestationFingerprint(), coordinates.keyId());
    }

    private static void fail(String reason) {
        throw new VerificationFailure(reason);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Coordinates(
            String attestationId,
            String attestationFingerprint,
            String keyId
    ) {
        private static Coordinates from(JsonNode value) {
            return value == null ? new Coordinates("", "", "")
                    : new Coordinates(value.at("/material/attestationId").asText(),
                    value.path("attestationFingerprint").asText(),
                    value.at("/seal/keyId").asText());
        }
    }

    private static final class VerificationFailure extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(reasonCode, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
