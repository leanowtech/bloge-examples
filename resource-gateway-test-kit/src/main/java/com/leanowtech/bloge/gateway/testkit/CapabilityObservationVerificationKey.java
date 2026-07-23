package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;

/**
 * Externally pinned producer key for capability-observation verification.
 *
 * @param schemaVersion verification-key wire version
 * @param keyId stable producer key id
 * @param algorithm fixed signature algorithm
 * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo bytes
 * @param issuer exact producer authority
 * @param notBefore inclusive signing-time bound
 * @param notAfter exclusive signing-time bound
 * @param state current key lifecycle
 */
public record CapabilityObservationVerificationKey(
        String schemaVersion,
        String keyId,
        String algorithm,
        String encodedPublicKey,
        String issuer,
        Instant notBefore,
        Instant notAfter,
        State state
) {
    /** Current observation verification-key compatibility version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityObservationVerificationKey.v1";

    /** Validates the externally provisioned public trust input. */
    public CapabilityObservationVerificationKey {
        schemaVersion = normalized(schemaVersion);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        encodedPublicKey = normalized(encodedPublicKey);
        issuer = normalized(issuer);
        notBefore = notBefore == null ? Instant.EPOCH : notBefore;
        notAfter = notAfter == null ? Instant.EPOCH : notAfter;
        state = state == null ? State.REVOKED : state;
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")
                || !"Ed25519".equals(algorithm)
                || !issuer.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")
                || !notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException(
                    "capability observation verification key is invalid");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedPublicKey);
            if (decoded.length == 0 || !encodedPublicKey.equals(
                    Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(
                        "capability observation public key is not canonical");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "capability observation public key must be canonical base64",
                    invalid);
        }
    }

    /** Producer-key lifecycle states. */
    public enum State {
        /** Key may sign and verify observations. */
        ACTIVE,
        /** Key may verify historical observations but must not sign new ones. */
        RETIRED,
        /** Key must not be trusted. */
        REVOKED
    }

    /**
     * Reports whether current lifecycle permits historical verification.
     *
     * @return true for active and retired keys
     */
    public boolean verificationAllowed() {
        return state == State.ACTIVE || state == State.RETIRED;
    }

    /**
     * Decodes one strict compatibility-fixture key.
     *
     * @param value exact key JSON
     * @return typed public verification key
     */
    public static CapabilityObservationVerificationKey from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 8) {
            throw new IllegalArgumentException(
                    "capability observation verification key is malformed");
        }
        try {
            return new CapabilityObservationVerificationKey(
                    value.path("schemaVersion").asText(),
                    value.path("keyId").asText(),
                    value.path("algorithm").asText(),
                    value.path("encodedPublicKey").asText(),
                    value.path("issuer").asText(),
                    Instant.parse(value.path("notBefore").asText()),
                    Instant.parse(value.path("notAfter").asText()),
                    State.valueOf(value.path("state").asText()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "capability observation verification key is malformed",
                    invalid);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
