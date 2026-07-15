package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Public verification key returned by the Resource Gateway integration protocol.
 *
 * @param schemaVersion key descriptor wire version
 * @param keyId stable signer key id
 * @param algorithm signature algorithm
 * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo bytes
 * @param createdAt key creation time
 * @param state ACTIVE or RETIRED verification state
 * @param provider producer-defined key provider name
 */
public record EvidenceVerificationKey(
        String schemaVersion,
        String keyId,
        String algorithm,
        String encodedPublicKey,
        Instant createdAt,
        String state,
        String provider
) {
    /** Normalizes and validates verification policy fields. */
    public EvidenceVerificationKey {
        schemaVersion = normalized(schemaVersion);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        encodedPublicKey = normalized(encodedPublicKey);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        state = normalized(state);
        provider = normalized(provider);
        if (!TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1.equals(schemaVersion)
                || keyId.isBlank() || algorithm.isBlank() || encodedPublicKey.isBlank()
                || Instant.EPOCH.equals(createdAt) || state.isBlank()) {
            throw new IllegalArgumentException("Evidence verification key is incomplete");
        }
    }

    /**
     * Decodes and validates an integration envelope containing one verification key.
     *
     * @param envelope integration protocol envelope
     * @param expectedKeyId path-bound expected key id
     * @return typed verification key
     */
    public static EvidenceVerificationKey fromEnvelope(JsonNode envelope, String expectedKeyId) {
        if (envelope == null || !envelope.isObject()
                || !"EVIDENCE_VERIFICATION_KEY".equals(envelope.path("payloadKind").asText())
                || !TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1.equals(
                envelope.path("payloadSchemaVersion").asText()) || !envelope.path("payload").isObject()) {
            throw new IllegalArgumentException("Evidence verification key envelope is invalid");
        }
        JsonNode value = envelope.path("payload");
        EvidenceVerificationKey key = new EvidenceVerificationKey(
                value.path("schemaVersion").asText(), value.path("keyId").asText(),
                value.path("algorithm").asText(), value.path("encodedPublicKey").asText(),
                instant(value.path("createdAt").asText()), value.path("state").asText(),
                value.path("provider").asText());
        if (!key.keyId().equals(normalized(expectedKeyId))) {
            throw new IllegalArgumentException("Evidence verification key identity is inconsistent");
        }
        return key;
    }

    /**
     * Indicates whether policy permits historical signature verification with this key.
     *
     * @return true for ACTIVE and RETIRED keys
     */
    public boolean verificationAllowed() {
        return List.of("ACTIVE", "RETIRED").contains(state);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(normalized(value));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Evidence verification key createdAt is invalid");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
