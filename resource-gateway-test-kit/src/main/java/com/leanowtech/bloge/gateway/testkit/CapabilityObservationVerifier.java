package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Dependency-light offline verifier for signed capability observations.
 *
 * <p>The verifier proves strict schema, deterministic use ordering, purpose and validity windows,
 * local full-scope equality, canonical fingerprints, key lifecycle, and Ed25519 signature. It does
 * not claim that referenced payloads exist or are sanitized; callers must separately integrate a
 * tenant-scoped payload-vault and sanitization-proof authority before corpus admission.</p>
 */
public final class CapabilityObservationVerifier {
    /** Maximum canonical signed material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 768 * 1024;
    /** Maximum canonical complete envelope size. */
    public static final int MAXIMUM_OBSERVATION_BYTES = 1024 * 1024;
    /** Maximum occurrence-to-signature delay. */
    public static final Duration MAXIMUM_ISSUANCE_DELAY = Duration.ofMinutes(15);
    /** Signature domain shared with observation producers. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_CAPABILITY_OBSERVATION_V1";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates a stateless independent verifier. */
    public CapabilityObservationVerifier() {
    }

    /** Closed verification outcome. */
    public enum Outcome {
        /** Every structural, scope, time, policy, fingerprint, and signature check passed. */
        VERIFIED,
        /** Structure, canonical content, or signature is invalid. */
        INVALID,
        /** No exact producer key was supplied. */
        KEY_UNAVAILABLE,
        /** Producer identity, algorithm, lifecycle, or key window rejected the envelope. */
        POLICY_REJECTED,
        /** Local complete enterprise scope differs from the signed scope. */
        SCOPE_MISMATCH,
        /** Grant, retention, issuance, or verification time is outside the signed window. */
        WINDOW_REJECTED
    }

    /**
     * Payload-free result suitable for CI and correctness workbooks.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable machine-readable reason
     * @param observationId observation id, or blank
     * @param observationFingerprint envelope fingerprint, or blank
     * @param keyId producer key id, or blank
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String observationId,
            String observationFingerprint,
            String keyId
    ) {
        /** Validates bounded log-safe result coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = normalized(reasonCode);
            observationId = normalized(observationId);
            observationFingerprint = normalized(observationFingerprint);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "capability observation verification result is invalid");
            }
        }

        /**
         * Reports whether every independent check passed.
         *
         * @return true only for a verified observation
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one decoded observation against local trust and scope.
     *
     * @param observation untrusted strict observation JSON
     * @param key externally pinned producer key; may be {@code null}
     * @param expectedScope immutable local enterprise scope
     * @param verificationTime trusted admission time
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode observation,
            CapabilityObservationVerificationKey key,
            CapabilityObservationScope expectedScope,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.from(observation);
        try {
            CapabilityMirrorSchemaValidator.require(
                    observation,
                    CapabilityMirrorProtocol.CAPABILITY_OBSERVATION_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OBSERVATION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "OBSERVATION_SCHEMA_INVALID", coordinates);
        }
        try {
            requireCanonicalUses(observation);
            verifyCanonicalBase64(observation.at("/seal/signature").asText());
            String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    signatureMaterial(observation), MAXIMUM_MATERIAL_BYTES);
            if (!materialFingerprint.equals(
                    observation.at("/seal/materialFingerprint").asText())) {
                return result(
                        Outcome.INVALID,
                        "OBSERVATION_MATERIAL_FINGERPRINT_INVALID",
                        coordinates);
            }
            String observationFingerprint = EvidenceVerificationSupport.sha256Bounded(
                    artifactMaterial(observation), MAXIMUM_OBSERVATION_BYTES);
            if (!observationFingerprint.equals(
                    observation.path("observationFingerprint").asText())) {
                return result(
                        Outcome.INVALID,
                        "OBSERVATION_FINGERPRINT_INVALID",
                        coordinates);
            }
        } catch (VerificationFailure invalid) {
            return result(Outcome.INVALID, invalid.reasonCode, coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID, "OBSERVATION_MATERIAL_INVALID", coordinates);
        }
        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "AUTHORITY_KEY_UNAVAILABLE",
                    coordinates);
        }
        JsonNode seal = observation.path("seal");
        if (!key.keyId().equals(seal.path("keyId").asText())
                || !key.issuer().equals(seal.path("issuer").asText())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "AUTHORITY_IDENTITY_MISMATCH",
                    coordinates);
        }
        if (!key.verificationAllowed()
                || !"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(seal.path("algorithm").asText())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_POLICY_REJECTED",
                    coordinates);
        }
        Instant signedAt = instant(seal.path("signedAt"));
        if (signedAt == null
                || signedAt.isBefore(key.notBefore())
                || !signedAt.isBefore(key.notAfter())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_OUTSIDE_VALIDITY",
                    coordinates);
        }
        if (expectedScope == null
                || !expectedScope.matches(observation.at("/material/scope"))) {
            return result(
                    Outcome.SCOPE_MISMATCH,
                    "OBSERVATION_SCOPE_MISMATCH",
                    coordinates);
        }
        if (!validWindow(observation, verificationTime)) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "OBSERVATION_WINDOW_REJECTED",
                    coordinates);
        }
        try {
            if (!EvidenceVerificationSupport.verifyEd25519(
                    seal.path("materialFingerprint").asText(),
                    seal.path("signature").asText(),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "OBSERVATION_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(
                    Outcome.INVALID,
                    "OBSERVATION_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static boolean validWindow(
            JsonNode observation, Instant verificationTime) {
        if (verificationTime == null) {
            return false;
        }
        Instant occurredAt = requiredInstant(
                observation.at("/material/occurredAt"));
        Instant signedAt = requiredInstant(observation.at("/seal/signedAt"));
        Instant grantFrom = requiredInstant(
                observation.at("/material/dataUseGrant/validFrom"));
        Instant grantUntil = requiredInstant(
                observation.at("/material/dataUseGrant/expiresAt"));
        if (signedAt.isBefore(occurredAt)
                || Duration.between(occurredAt, signedAt)
                .compareTo(MAXIMUM_ISSUANCE_DELAY) > 0
                || occurredAt.isBefore(grantFrom)
                || !occurredAt.isBefore(grantUntil)
                || signedAt.isBefore(grantFrom)
                || !signedAt.isBefore(grantUntil)
                || verificationTime.isBefore(grantFrom)
                || !verificationTime.isBefore(grantUntil)) {
            return false;
        }
        Instant requestRetention = requiredInstant(
                observation.at("/material/request/retentionUntil"));
        if (!requestRetention.isAfter(signedAt)
                || !requestRetention.isAfter(verificationTime)) {
            return false;
        }
        JsonNode response = observation.at("/material/response");
        if (!response.isNull()) {
            Instant responseRetention = requiredInstant(
                    response.path("retentionUntil"));
            if (!responseRetention.isAfter(signedAt)
                    || !responseRetention.isAfter(verificationTime)) {
                return false;
            }
        }
        return true;
    }

    private static void requireCanonicalUses(JsonNode observation) {
        JsonNode uses = observation.at("/material/dataUseGrant/allowedUses");
        JsonNode previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode use : uses) {
            if (previous != null
                    && Comparator.comparing((JsonNode value) -> value.asText())
                    .compare(previous, use) > 0
                    || !seen.add(use.asText())) {
                fail("OBSERVATION_ALLOWED_USES_ORDER_INVALID");
            }
            previous = use;
        }
    }

    private static ObjectNode signatureMaterial(JsonNode observation) {
        ObjectNode value = JSON.createObjectNode();
        value.put("domain", SIGNATURE_DOMAIN);
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol.CAPABILITY_OBSERVATION_V1);
        value.set("material", observation.path("material").deepCopy());
        return value;
    }

    private static JsonNode artifactMaterial(JsonNode observation) {
        ObjectNode value = ((ObjectNode) observation).deepCopy();
        value.put("observationFingerprint", "");
        return value;
    }

    private static Instant requiredInstant(JsonNode value) {
        Instant exact = instant(value);
        if (exact == null
                || Instant.EPOCH.equals(exact)
                || !exact.toString().equals(value.asText())) {
            fail("OBSERVATION_TIME_INVALID");
        }
        return exact;
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static void verifyCanonicalBase64(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(Base64.getEncoder().encodeToString(decoded))) {
                fail("OBSERVATION_SIGNATURE_ENCODING_INVALID");
            }
        } catch (IllegalArgumentException invalid) {
            fail("OBSERVATION_SIGNATURE_ENCODING_INVALID");
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.observationId(),
                coordinates.observationFingerprint(),
                coordinates.keyId());
    }

    private static void fail(String reason) {
        throw new VerificationFailure(reason);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Coordinates(
            String observationId,
            String observationFingerprint,
            String keyId
    ) {
        private static Coordinates from(JsonNode value) {
            return value == null
                    ? new Coordinates("", "", "")
                    : new Coordinates(
                    value.at("/material/observationId").asText(),
                    value.path("observationFingerprint").asText(),
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
