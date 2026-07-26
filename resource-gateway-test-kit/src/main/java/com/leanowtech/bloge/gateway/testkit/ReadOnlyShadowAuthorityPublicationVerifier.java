package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Dependency-light offline verifier for read-only Shadow online-authority publications.
 *
 * <p>The verifier treats decoded JSON as hostile. It independently applies the packaged strict
 * schema, recomputes domain-separated material and complete-publication fingerprints, requires an
 * exact locally observed current head and full enterprise scope, enforces bounded activation and
 * expiry, applies local key lifecycle, and verifies the Ed25519 signature. It never returns
 * business payload or trusts a key advertised by the publication itself.</p>
 */
public final class ReadOnlyShadowAuthorityPublicationVerifier {
    /** Maximum canonical signed decision material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 512 * 1024;
    /** Maximum canonical complete publication size. */
    public static final int MAXIMUM_PUBLICATION_BYTES = 1024 * 1024;
    /** Maximum grant and guard-policy lifetime. */
    public static final Duration MAXIMUM_AUTHORIZATION_LIFETIME =
            Duration.ofHours(24);
    /** Maximum kill-switch lifetime. */
    public static final Duration MAXIMUM_KILL_SWITCH_LIFETIME =
            Duration.ofMinutes(15);

    private static final Duration MAXIMUM_AUTHORIZATION_ACTIVATION_DELAY =
            Duration.ofMinutes(5);
    private static final Duration MAXIMUM_KILL_SWITCH_ACTIVATION_DELAY =
            Duration.ofMinutes(2);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates one stateless offline verifier. */
    public ReadOnlyShadowAuthorityPublicationVerifier() {
    }

    /** Bounded publication-verification outcomes. */
    public enum Outcome {
        /** Every schema, identity, content, time, key, and signature check passed. */
        VERIFIED,
        /** Schema, canonical material, fingerprint, or signature is invalid. */
        INVALID,
        /** Exact current-head protocol, scope, issuer, stream, or revision does not match. */
        BINDING_MISMATCH,
        /** No independently provisioned authority key was supplied. */
        KEY_UNAVAILABLE,
        /** Local authority-key identity, algorithm, lifecycle, or signing window was rejected. */
        KEY_POLICY_REJECTED,
        /** Publication is not active at the trusted verification instant. */
        WINDOW_REJECTED
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param streamId bounded stream identity, or blank when unavailable
     * @param revision publication revision, or zero when unavailable
     * @param publicationFingerprint complete publication fingerprint, or blank
     * @param keyId authority key identity, or blank
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String streamId,
            long revision,
            String publicationFingerprint,
            String keyId
    ) {
        /** Validates one log-safe result without exposing publication content. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = bounded(reasonCode, 255);
            streamId = boundedOptional(streamId, 512);
            publicationFingerprint = fingerprintOptional(publicationFingerprint);
            keyId = boundedOptional(keyId, 512);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}") || revision < 0) {
                throw new IllegalArgumentException(
                        "read-only Shadow authority verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only for a fully verified exact current head
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one untrusted publication against local current-head trust.
     *
     * @param publication untrusted decoded publication JSON
     * @param binding exact locally observed current-head and enterprise binding
     * @param authorityKey independently provisioned public verification key
     * @param verificationTime trusted current time
     * @return bounded payload-free result
     */
    public VerificationResult verify(
            JsonNode publication,
            ReadOnlyShadowAuthorityBinding binding,
            ReadOnlyShadowAuthorityVerificationKey authorityKey,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.from(publication, binding);
        if (binding == null) {
            return result(
                    Outcome.BINDING_MISMATCH,
                    "EXPECTED_BINDING_UNAVAILABLE",
                    coordinates);
        }
        try {
            CapabilityMirrorSchemaValidator.require(
                    publication,
                    binding.type().schemaResource(),
                    "RG.MIRROR.CLIENT.SHADOW_AUTHORITY_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_SCHEMA_INVALID",
                    coordinates);
        }
        if (!binding.matches(publication)) {
            return result(
                    Outcome.BINDING_MISMATCH,
                    "PUBLICATION_CURRENT_HEAD_BINDING_MISMATCH",
                    coordinates);
        }
        JsonNode material = publication.path("material");
        JsonNode seal = publication.path("seal");
        try {
            String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    signatureMaterial(binding.type(), publication),
                    MAXIMUM_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    publication.path("materialFingerprint").asText())
                    || !materialFingerprint.equals(
                    seal.path("materialFingerprint").asText())) {
                return result(
                        Outcome.INVALID,
                        "PUBLICATION_MATERIAL_FINGERPRINT_INVALID",
                        coordinates);
            }
            String publicationFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            publicationMaterial(publication),
                            MAXIMUM_PUBLICATION_BYTES);
            if (!publicationFingerprint.equals(
                    publication.path("publicationFingerprint").asText())) {
                return result(
                        Outcome.INVALID,
                        "PUBLICATION_FINGERPRINT_INVALID",
                        coordinates);
            }
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_CANONICAL_MATERIAL_INVALID",
                    coordinates);
        }

        Instant issuedAt;
        Instant effectiveAt;
        Instant expiresAt;
        Instant signedAt;
        try {
            issuedAt = Instant.parse(material.path("issuedAt").asText());
            effectiveAt = Instant.parse(material.path(
                    binding.type() == ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH
                            ? "effectiveAt" : "validFrom").asText());
            expiresAt = Instant.parse(material.path("expiresAt").asText());
            signedAt = Instant.parse(seal.path("signedAt").asText());
            requireWindow(binding.type(), issuedAt, effectiveAt, expiresAt, signedAt);
            requireLimits(binding.type(), material);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_POLICY_INVALID",
                    coordinates);
        }
        Instant activeFrom = effectiveAt.isAfter(signedAt) ? effectiveAt : signedAt;
        if (verificationTime == null
                || verificationTime.isBefore(activeFrom)
                || !verificationTime.isBefore(expiresAt)) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "PUBLICATION_OUTSIDE_VALIDITY_WINDOW",
                    coordinates);
        }
        if (authorityKey == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "AUTHORITY_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!authorityKey.keyId().equals(seal.path("keyId").asText())
                || !authorityKey.issuer().equals(material.path("issuer").asText())
                || !authorityKey.scope().equals(binding.scope())
                || authorityKey.publicationType() != binding.type()
                || !authorityKey.algorithm().equals(seal.path("algorithm").asText())
                || !authorityKey.verificationAllowed(signedAt)
                || signedAt.isBefore(authorityKey.notBefore())
                || !signedAt.isBefore(authorityKey.notAfter())) {
            return result(
                    Outcome.KEY_POLICY_REJECTED,
                    "AUTHORITY_KEY_POLICY_REJECTED",
                    coordinates);
        }
        try {
            requireCanonicalBase64(
                    seal.path("signature").asText());
            if (!EvidenceVerificationSupport.verifyEd25519(
                    publication.path("materialFingerprint").asText(),
                    seal.path("signature").asText(),
                    authorityKey.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "AUTHORITY_SIGNATURE_INVALID",
                        coordinates);
            }
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "AUTHORITY_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates);
    }

    private static void requireCanonicalBase64(String value) {
        byte[] decoded = java.util.Base64.getDecoder()
                .decode(value);
        if (decoded.length == 0
                || !value.equals(
                java.util.Base64.getEncoder()
                        .encodeToString(decoded))) {
            throw new IllegalArgumentException(
                    "authority signature is not canonical base64");
        }
    }

    private static ObjectNode signatureMaterial(
            ReadOnlyShadowAuthorityBinding.Type type,
            JsonNode publication) {
        ObjectNode value = JSON.createObjectNode();
        value.put("domain", type.signatureDomain());
        value.put("schemaVersion", publication.path("schemaVersion").asText());
        value.set("material", publication.path("material"));
        return value;
    }

    private static ObjectNode publicationMaterial(JsonNode publication) {
        ObjectNode value = JSON.createObjectNode();
        value.put("schemaVersion", publication.path("schemaVersion").asText());
        value.put("publicationFingerprint", "");
        value.put(
                "materialFingerprint",
                publication.path("materialFingerprint").asText());
        value.set("material", publication.path("material"));
        value.set("seal", publication.path("seal"));
        return value;
    }

    private static void requireWindow(
            ReadOnlyShadowAuthorityBinding.Type type,
            Instant issuedAt,
            Instant effectiveAt,
            Instant expiresAt,
            Instant signedAt) {
        Duration maximumActivation =
                type == ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH
                        ? MAXIMUM_KILL_SWITCH_ACTIVATION_DELAY
                        : MAXIMUM_AUTHORIZATION_ACTIVATION_DELAY;
        Duration maximumLifetime =
                type == ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH
                        ? MAXIMUM_KILL_SWITCH_LIFETIME
                        : MAXIMUM_AUTHORIZATION_LIFETIME;
        if (Instant.EPOCH.equals(issuedAt)
                || effectiveAt.isBefore(issuedAt)
                || Duration.between(issuedAt, effectiveAt)
                .compareTo(maximumActivation) > 0
                || !expiresAt.isAfter(effectiveAt)
                || Duration.between(issuedAt, expiresAt)
                .compareTo(maximumLifetime) > 0
                || signedAt.isBefore(issuedAt)
                || !signedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "authority publication window is invalid");
        }
    }

    private static void requireLimits(
            ReadOnlyShadowAuthorityBinding.Type type,
            JsonNode material) {
        if (type != ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY) {
            return;
        }
        JsonNode limits = material.path("limits");
        Duration startWindow = Duration.parse(
                limits.path("startWindow").asText());
        Duration coolDown = Duration.parse(
                limits.path("circuitCoolDown").asText());
        if (startWindow.isZero()
                || startWindow.isNegative()
                || startWindow.compareTo(Duration.ofHours(24)) > 0
                || coolDown.isZero()
                || coolDown.isNegative()
                || coolDown.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "guard-policy durations are outside bounds");
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.streamId(),
                coordinates.revision(),
                coordinates.publicationFingerprint(),
                coordinates.keyId());
    }

    private static String bounded(String value, int maximum) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > maximum) {
            throw new IllegalArgumentException("required result field is invalid");
        }
        return exact;
    }

    private static String boundedOptional(String value, int maximum) {
        String exact = value == null ? "" : value.trim();
        return exact.length() > maximum ? "" : exact;
    }

    private static String fingerprintOptional(String value) {
        String exact = boundedOptional(value, 71);
        return exact.matches("sha256:[a-f0-9]{64}") ? exact : "";
    }

    private record Coordinates(
            String streamId,
            long revision,
            String publicationFingerprint,
            String keyId
    ) {
        private static Coordinates from(
                JsonNode publication,
                ReadOnlyShadowAuthorityBinding binding) {
            if (publication == null || !publication.isObject() || binding == null) {
                return new Coordinates("", 0, "", "");
            }
            JsonNode material = publication.path("material");
            return new Coordinates(
                    boundedOptional(
                            material.path(binding.type().streamIdField()).asText(),
                            512),
                    Math.max(0, material.path("revision").asLong()),
                    fingerprintOptional(
                            publication.path("publicationFingerprint").asText()),
                    boundedOptional(publication.at("/seal/keyId").asText(), 512));
        }
    }
}
