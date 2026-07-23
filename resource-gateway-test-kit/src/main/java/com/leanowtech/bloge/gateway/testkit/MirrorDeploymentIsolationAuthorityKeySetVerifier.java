package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Dependency-light offline verifier for isolation-authority key-set publications.
 *
 * <p>The verifier independently checks the packaged strict schema, deterministic ordering,
 * canonical fingerprints, full enterprise/deployment binding, short validity windows, monotonic
 * publication history, all supplied bootstrap-root signatures, and the exact locally configured
 * M-of-N threshold. It exposes advertised attestation keys only after every check passes.</p>
 */
public final class MirrorDeploymentIsolationAuthorityKeySetVerifier {
    /** Maximum canonical signed material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 1024 * 1024;
    /** Maximum canonical complete publication size. */
    public static final int MAXIMUM_PUBLICATION_BYTES = 2 * 1024 * 1024;
    /** Maximum v1 publication lifetime. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofHours(24);
    /** Maximum issuance-to-activation delay. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY = Duration.ofMinutes(5);
    /** Signature domain shared with external bootstrap-root issuers. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_V1";

    private static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates a stateless offline verifier. */
    public MirrorDeploymentIsolationAuthorityKeySetVerifier() {
    }

    /** Bounded publication-verification outcome. */
    public enum Outcome {
        /** Every structural, identity, chain, time, policy, threshold, and signature check passed. */
        VERIFIED,
        /** Schema, canonical material, fingerprint, or signature is invalid. */
        INVALID,
        /** Locally pinned bootstrap-root keys are unavailable. */
        ROOTS_UNAVAILABLE,
        /** Local trust, threshold, or lifecycle policy rejected the publication. */
        POLICY_REJECTED,
        /** Enterprise scope, deployment, issuer, key-set, or trust-domain binding drifted. */
        IDENTITY_MISMATCH,
        /** Publication is not active at the trusted verification time. */
        WINDOW_REJECTED,
        /** Monotonic generation, predecessor, or trusted-floor checks failed. */
        CHAIN_REJECTED
    }

    /**
     * Last durably accepted publication coordinate used to reject rollback and forks.
     *
     * @param keySetId stable key-set stream identity
     * @param generation positive accepted generation
     * @param publicationFingerprint exact accepted publication fingerprint
     */
    public record TrustedFloor(
            String keySetId,
            long generation,
            String publicationFingerprint
    ) {
        /** Validates one exact anti-rollback floor. */
        public TrustedFloor {
            keySetId = identifier(keySetId, "floor.keySetId");
            if (generation < 1) {
                throw new IllegalArgumentException("floor generation must be positive");
            }
            publicationFingerprint = fingerprint(publicationFingerprint,
                    "floor.publicationFingerprint");
        }
    }

    /**
     * Payload-free result plus verified public isolation-attestation keys.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param keySetId publication stream identity, or blank when unavailable
     * @param generation publication generation, or zero when unavailable
     * @param publicationFingerprint publication fingerprint, or blank when unavailable
     * @param authorityKeys verified public attestation keys; empty on every failure
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String keySetId,
            long generation,
            String publicationFingerprint,
            List<MirrorDeploymentIsolationVerificationKey> authorityKeys
    ) {
        /** Validates bounded coordinates and prevents failed verification from exposing keys. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = normalized(reasonCode);
            keySetId = normalized(keySetId);
            publicationFingerprint = normalized(publicationFingerprint);
            authorityKeys = authorityKeys == null ? List.of() : List.copyOf(authorityKeys);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    || outcome != Outcome.VERIFIED && !authorityKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "isolation authority key-set verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only for a fully verified publication
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }

        /**
         * Finds one verified isolation-attestation key.
         *
         * @param keyId exact advertised key identity
         * @return verified key, or empty on failure or when absent
         */
        public Optional<MirrorDeploymentIsolationVerificationKey> authorityKey(String keyId) {
            if (!verified()) {
                return Optional.empty();
            }
            return authorityKeys.stream().filter(key -> key.keyId().equals(keyId)).findFirst();
        }
    }

    /**
     * Independently verifies one decoded publication against immutable local trust policy.
     *
     * @param publication untrusted decoded publication
     * @param binding exact local scope, deployment, issuer, and threshold binding
     * @param roots locally pinned bootstrap-root public keys
     * @param floor last durably accepted publication, or {@code null} only for bootstrap
     * @param verificationTime current trusted time
     * @return bounded result exposing public attestation keys only after complete verification
     */
    public VerificationResult verify(
            JsonNode publication,
            MirrorDeploymentIsolationAuthorityKeySetBinding binding,
            List<MirrorDeploymentIsolationRootVerificationKey> roots,
            TrustedFloor floor,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.from(publication);
        try {
            CapabilityMirrorSchemaValidator.require(publication,
                    CapabilityMirrorProtocol
                            .MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.DEPLOYMENT_ISOLATION_AUTHORITY_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "PUBLICATION_SCHEMA_INVALID", coordinates, List.of());
        }
        try {
            verifyProtocolMaterial(publication);
            String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    signatureMaterial(publication), MAXIMUM_MATERIAL_BYTES);
            if (!materialFingerprint.equals(publication.path("materialFingerprint").asText())) {
                return result(Outcome.INVALID, "PUBLICATION_MATERIAL_FINGERPRINT_INVALID",
                        coordinates, List.of());
            }
            String publicationFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    publicationMaterial(publication), MAXIMUM_PUBLICATION_BYTES);
            if (!publicationFingerprint.equals(
                    publication.path("publicationFingerprint").asText())) {
                return result(Outcome.INVALID, "PUBLICATION_FINGERPRINT_INVALID",
                        coordinates, List.of());
            }
        } catch (VerificationFailure failure) {
            return result(Outcome.INVALID, failure.reasonCode, coordinates, List.of());
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "PUBLICATION_MATERIAL_INVALID",
                    coordinates, List.of());
        }
        if (binding == null) {
            return result(Outcome.POLICY_REJECTED, "EXPECTED_BINDING_UNAVAILABLE",
                    coordinates, List.of());
        }
        JsonNode material = publication.path("material");
        if (!binding.matches(material)) {
            return result(Outcome.IDENTITY_MISMATCH, "PUBLICATION_BINDING_MISMATCH",
                    coordinates, List.of());
        }
        if (verificationTime == null
                || verificationTime.isBefore(requiredInstant(material.path("notBefore")))
                || !verificationTime.isBefore(requiredInstant(material.path("expiresAt")))) {
            return result(Outcome.WINDOW_REJECTED, "PUBLICATION_OUTSIDE_VALIDITY_WINDOW",
                    coordinates, List.of());
        }
        String chainFailure = chainFailure(publication, floor);
        if (!chainFailure.isBlank()) {
            return result(Outcome.CHAIN_REJECTED, chainFailure, coordinates, List.of());
        }
        if (roots == null || roots.isEmpty()) {
            return result(Outcome.ROOTS_UNAVAILABLE, "BOOTSTRAP_ROOTS_UNAVAILABLE",
                    coordinates, List.of());
        }
        Map<RootCoordinate, MirrorDeploymentIsolationRootVerificationKey> rootsByCoordinate =
                new HashMap<>();
        Set<String> rootPublicKeys = new HashSet<>();
        for (MirrorDeploymentIsolationRootVerificationKey root : roots) {
            if (root == null || rootsByCoordinate.put(
                    new RootCoordinate(root.authorityId(), root.keyId()), root) != null
                    || !rootPublicKeys.add(
                    root.algorithm() + '\0' + root.encodedPublicKey())) {
                return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOTS_AMBIGUOUS",
                        coordinates, List.of());
            }
        }
        for (JsonNode signature : publication.path("signatures")) {
            MirrorDeploymentIsolationRootVerificationKey root = rootsByCoordinate.get(
                    new RootCoordinate(signature.path("authorityId").asText(),
                            signature.path("keyId").asText()));
            if (root == null) {
                return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_UNKNOWN",
                        coordinates, List.of());
            }
            Instant signedAt = requiredInstant(signature.path("signedAt"));
            if (!root.verificationAllowed()
                    || !root.algorithm().equals(signature.path("algorithm").asText())
                    || signedAt.isBefore(root.notBefore())
                    || !signedAt.isBefore(root.notAfter())) {
                return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_POLICY_REJECTED",
                        coordinates, List.of());
            }
            try {
                if (!EvidenceVerificationSupport.verifyEd25519(
                        publication.path("materialFingerprint").asText(),
                        signature.path("signature").asText(), root.encodedPublicKey())) {
                    return result(Outcome.INVALID, "BOOTSTRAP_ROOT_SIGNATURE_INVALID",
                            coordinates, List.of());
                }
            } catch (RuntimeException | GeneralSecurityException invalid) {
                return result(Outcome.INVALID, "BOOTSTRAP_ROOT_SIGNATURE_MATERIAL_INVALID",
                        coordinates, List.of());
            }
        }
        if (publication.path("signatures").size() < binding.rootThreshold()) {
            return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_THRESHOLD_NOT_MET",
                    coordinates, List.of());
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates,
                decodeAuthorityKeys(material, binding.attestationIssuer()));
    }

    private static void verifyProtocolMaterial(JsonNode publication) {
        JsonNode material = publication.path("material");
        Instant issuedAt = requiredInstant(material.path("issuedAt"));
        Instant notBefore = requiredInstant(material.path("notBefore"));
        Instant expiresAt = requiredInstant(material.path("expiresAt"));
        if (notBefore.isBefore(issuedAt)
                || Duration.between(issuedAt, notBefore)
                .compareTo(MAXIMUM_ACTIVATION_DELAY) > 0
                || !expiresAt.isAfter(notBefore)
                || Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            fail("PUBLICATION_WINDOW_INVALID");
        }
        int threshold = material.path("rootThreshold").asInt();
        if (publication.path("signatures").size() < threshold) {
            fail("PUBLICATION_THRESHOLD_INVALID");
        }
        String previousKeyId = null;
        boolean activeCoverage = false;
        for (JsonNode key : material.path("authorityKeys")) {
            String keyId = key.path("keyId").asText();
            if (previousKeyId != null && previousKeyId.compareTo(keyId) >= 0) {
                fail("PUBLICATION_AUTHORITY_KEY_ORDER_INVALID");
            }
            previousKeyId = keyId;
            verifyCanonicalBase64(key.path("encodedPublicKey").asText(),
                    "PUBLICATION_AUTHORITY_KEY_ENCODING_INVALID");
            Instant keyNotBefore = requiredInstant(key.path("notBefore"));
            Instant keyNotAfter = requiredInstant(key.path("notAfter"));
            if (!keyNotAfter.isAfter(keyNotBefore)) {
                fail("PUBLICATION_AUTHORITY_KEY_WINDOW_INVALID");
            }
            if ("ACTIVE".equals(key.path("state").asText())
                    && !keyNotBefore.isAfter(notBefore) && !keyNotAfter.isBefore(expiresAt)) {
                activeCoverage = true;
            }
        }
        if (!activeCoverage) {
            fail("PUBLICATION_ACTIVE_AUTHORITY_KEY_UNAVAILABLE");
        }
        String previousCoordinate = null;
        Set<String> authorities = new HashSet<>();
        for (JsonNode signature : publication.path("signatures")) {
            String authority = signature.path("authorityId").asText();
            String coordinate = authority + '\0' + signature.path("keyId").asText();
            if (previousCoordinate != null && previousCoordinate.compareTo(coordinate) > 0
                    || !authorities.add(authority)) {
                fail("PUBLICATION_ROOT_SIGNATURE_ORDER_INVALID");
            }
            previousCoordinate = coordinate;
            Instant signedAt = requiredInstant(signature.path("signedAt"));
            if (signedAt.isBefore(issuedAt) || signedAt.isAfter(notBefore)) {
                fail("PUBLICATION_ROOT_SIGNATURE_WINDOW_INVALID");
            }
            verifyCanonicalBase64(signature.path("signature").asText(),
                    "PUBLICATION_ROOT_SIGNATURE_ENCODING_INVALID");
        }
    }

    private static String chainFailure(JsonNode publication, TrustedFloor floor) {
        JsonNode material = publication.path("material");
        long generation = material.path("generation").asLong();
        if (floor == null) {
            return generation == 1 ? "" : "PUBLICATION_BOOTSTRAP_GENERATION_INVALID";
        }
        if (!floor.keySetId().equals(material.path("keySetId").asText())) {
            return "PUBLICATION_FLOOR_KEY_SET_MISMATCH";
        }
        if (generation == floor.generation()) {
            return publication.path("publicationFingerprint").asText()
                    .equals(floor.publicationFingerprint())
                    ? "" : "PUBLICATION_GENERATION_FORK";
        }
        if (generation < floor.generation()) {
            return "PUBLICATION_GENERATION_ROLLBACK";
        }
        if (generation > floor.generation() + 1) {
            return "PUBLICATION_GENERATION_GAP";
        }
        return material.path("previousPublicationFingerprint").asText()
                .equals(floor.publicationFingerprint())
                ? "" : "PUBLICATION_PREDECESSOR_MISMATCH";
    }

    private static List<MirrorDeploymentIsolationVerificationKey> decodeAuthorityKeys(
            JsonNode material, String issuer) {
        List<MirrorDeploymentIsolationVerificationKey> keys = new ArrayList<>();
        for (JsonNode key : material.path("authorityKeys")) {
            keys.add(new MirrorDeploymentIsolationVerificationKey(
                    MirrorDeploymentIsolationVerificationKey.SCHEMA_VERSION,
                    key.path("keyId").asText(), key.path("algorithm").asText(),
                    key.path("encodedPublicKey").asText(), issuer,
                    requiredInstant(key.path("notBefore")), requiredInstant(key.path("notAfter")),
                    MirrorDeploymentIsolationVerificationKey.State.valueOf(
                            key.path("state").asText())));
        }
        return List.copyOf(keys);
    }

    private static ObjectNode signatureMaterial(JsonNode publication) {
        ObjectNode value = JSON.createObjectNode();
        value.put("domain", SIGNATURE_DOMAIN);
        value.put("schemaVersion", SCHEMA_VERSION);
        value.set("material", publication.path("material").deepCopy());
        return value;
    }

    private static JsonNode publicationMaterial(JsonNode publication) {
        ObjectNode value = ((ObjectNode) publication).deepCopy();
        value.put("publicationFingerprint", "");
        return value;
    }

    private static Instant requiredInstant(JsonNode value) {
        String encoded = value.asText();
        try {
            Instant exact = Instant.parse(encoded);
            if (Instant.EPOCH.equals(exact) || !exact.toString().equals(encoded)) {
                fail("PUBLICATION_TIME_INVALID");
            }
            return exact;
        } catch (DateTimeParseException invalid) {
            fail("PUBLICATION_TIME_INVALID");
            throw new IllegalStateException("unreachable", invalid);
        }
    }

    private static void verifyCanonicalBase64(String value, String reason) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(Base64.getEncoder().encodeToString(decoded))) {
                fail(reason);
            }
        } catch (IllegalArgumentException invalid) {
            fail(reason);
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates,
            List<MirrorDeploymentIsolationVerificationKey> keys) {
        return new VerificationResult(outcome, reason, coordinates.keySetId(),
                coordinates.generation(), coordinates.publicationFingerprint(), keys);
    }

    private static void fail(String reason) {
        throw new VerificationFailure(reason);
    }

    private static String fingerprint(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record RootCoordinate(String authorityId, String keyId) {
    }

    private record Coordinates(
            String keySetId,
            long generation,
            String publicationFingerprint
    ) {
        private static Coordinates from(JsonNode value) {
            return value == null ? new Coordinates("", 0, "")
                    : new Coordinates(value.at("/material/keySetId").asText(),
                    value.at("/material/generation").asLong(),
                    value.path("publicationFingerprint").asText());
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
