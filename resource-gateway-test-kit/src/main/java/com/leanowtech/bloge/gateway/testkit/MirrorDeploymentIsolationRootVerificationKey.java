package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * Locally pinned public key for one independent isolation-publication bootstrap root.
 *
 * @param schemaVersion root-key wire version
 * @param authorityId independent bootstrap-root authority identity
 * @param keyId exact root key identity
 * @param algorithm fixed signature algorithm
 * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
 * @param notBefore inclusive root-signing validity bound
 * @param notAfter exclusive root-signing validity bound
 * @param state ACTIVE, RETIRED, or REVOKED local lifecycle state
 */
public record MirrorDeploymentIsolationRootVerificationKey(
        String schemaVersion,
        String authorityId,
        String keyId,
        String algorithm,
        String encodedPublicKey,
        Instant notBefore,
        Instant notAfter,
        State state
) {
    /** Current bootstrap-root compatibility wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationRootVerificationKey.v1";

    /** Validates one bounded public bootstrap-root policy. */
    public MirrorDeploymentIsolationRootVerificationKey {
        schemaVersion = normalized(schemaVersion);
        authorityId = normalized(authorityId);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        encodedPublicKey = normalized(encodedPublicKey);
        notBefore = notBefore == null ? Instant.EPOCH : notBefore;
        notAfter = notAfter == null ? Instant.EPOCH : notAfter;
        state = state == null ? State.REVOKED : state;
        if (!SCHEMA_VERSION.equals(schemaVersion) || !identifier(authorityId)
                || !identifier(keyId) || !"Ed25519".equals(algorithm)
                || !notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException("isolation bootstrap-root key is invalid");
        }
        requireCanonicalBase64(encodedPublicKey);
    }

    /** Bootstrap-root lifecycle states. */
    public enum State {
        /** Root may sign and verify current publications. */
        ACTIVE,
        /** Root may verify historical publications but must not sign new ones. */
        RETIRED,
        /** Root must not verify any publication. */
        REVOKED
    }

    /**
     * Reports whether local lifecycle policy permits verification.
     *
     * @return true for active or retired roots
     */
    public boolean verificationAllowed() {
        return state == State.ACTIVE || state == State.RETIRED;
    }

    /**
     * Decodes one strict compatibility-fixture root key.
     *
     * @param value root-key JSON value
     * @return typed public root key
     */
    public static MirrorDeploymentIsolationRootVerificationKey from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 8
                || !Set.of("schemaVersion", "authorityId", "keyId", "algorithm",
                "encodedPublicKey", "notBefore", "notAfter", "state")
                .equals(fieldNames(value))) {
            throw new IllegalArgumentException("isolation bootstrap-root key is malformed");
        }
        try {
            return new MirrorDeploymentIsolationRootVerificationKey(
                    value.path("schemaVersion").asText(), value.path("authorityId").asText(),
                    value.path("keyId").asText(), value.path("algorithm").asText(),
                    value.path("encodedPublicKey").asText(),
                    Instant.parse(value.path("notBefore").asText()),
                    Instant.parse(value.path("notAfter").asText()),
                    State.valueOf(value.path("state").asText()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "isolation bootstrap-root key is malformed", invalid);
        }
    }

    private static void requireCanonicalBase64(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(
                        "isolation bootstrap-root public key is not canonical");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "isolation bootstrap-root public key must be canonical base64", invalid);
        }
    }

    private static boolean identifier(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");
    }

    private static Set<String> fieldNames(JsonNode value) {
        HashSet<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
