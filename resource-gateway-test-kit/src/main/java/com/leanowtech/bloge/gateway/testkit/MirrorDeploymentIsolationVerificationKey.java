package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;

/**
 * Externally pinned SRE/security authority key for deployment-isolation attestations.
 *
 * @param schemaVersion authority-key wire version
 * @param keyId stable authority key identity
 * @param algorithm fixed signature algorithm
 * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo bytes
 * @param issuer exact isolation authority identity
 * @param notBefore inclusive signing-time bound
 * @param notAfter exclusive signing-time bound
 * @param state ACTIVE, RETIRED, or REVOKED lifecycle state
 */
public record MirrorDeploymentIsolationVerificationKey(
        String schemaVersion,
        String keyId,
        String algorithm,
        String encodedPublicKey,
        String issuer,
        Instant notBefore,
        Instant notAfter,
        State state
) {
    /** Current authority-key compatibility wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationVerificationKey.v1";

    /** Validates the externally supplied trust root. */
    public MirrorDeploymentIsolationVerificationKey {
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
                || !"Ed25519".equals(algorithm) || issuer.isBlank()
                || !notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException(
                    "deployment isolation verification key is invalid");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedPublicKey);
            if (decoded.length == 0 || !encodedPublicKey.equals(
                    Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(
                        "deployment isolation public key is not canonical");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "deployment isolation public key must be canonical base64", invalid);
        }
    }

    /** Authority-key lifecycle states. */
    public enum State {
        /** Key may sign new attestations and verify historical attestations. */
        ACTIVE,
        /** Key may verify historical attestations but must not sign new ones. */
        RETIRED,
        /** Key must not be trusted for verification. */
        REVOKED
    }

    /**
     * Reports whether current lifecycle policy permits historical verification.
     *
     * @return whether current policy permits historical signature verification
     */
    public boolean verificationAllowed() {
        return state == State.ACTIVE || state == State.RETIRED;
    }

    /**
     * Decodes one strict compatibility-fixture key.
     *
     * @param value authority-key JSON value
     * @return typed verification key
     */
    public static MirrorDeploymentIsolationVerificationKey from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 8) {
            throw new IllegalArgumentException(
                    "deployment isolation verification key is malformed");
        }
        try {
            return new MirrorDeploymentIsolationVerificationKey(
                    value.path("schemaVersion").asText(), value.path("keyId").asText(),
                    value.path("algorithm").asText(), value.path("encodedPublicKey").asText(),
                    value.path("issuer").asText(),
                    Instant.parse(value.path("notBefore").asText()),
                    Instant.parse(value.path("notAfter").asText()),
                    State.valueOf(value.path("state").asText()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "deployment isolation verification key is malformed", invalid);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
