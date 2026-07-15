package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Portable terminal suite evidence with child payloads deliberately omitted.
 *
 * @param suiteRunId durable aggregate run id
 * @param bundleFingerprint canonical bundle material fingerprint
 * @param payloadPolicy explicit child payload handling
 * @param attestation terminal aggregate signature manifest
 * @param evidence aggregate evidence JSON used for independent fingerprint verification
 * @param rawResponse defensive complete bundle response
 */
public record TestSuiteEvidenceBundle(
        String suiteRunId,
        String bundleFingerprint,
        PayloadPolicy payloadPolicy,
        TestSuiteRunAttestation attestation,
        JsonNode evidence,
        JsonNode rawResponse
) {
    /** Portable bundle payload policy. */
    public enum PayloadPolicy {
        /** Child request and response payloads remain in governed server storage. */
        OMITTED
    }

    /** Normalizes identity and protects decoded JSON from caller mutation. */
    public TestSuiteEvidenceBundle {
        suiteRunId = normalized(suiteRunId);
        bundleFingerprint = normalized(bundleFingerprint);
        if (suiteRunId.isBlank() || !fingerprint(bundleFingerprint) || payloadPolicy == null
                || attestation == null || evidence == null || !evidence.isObject()
                || !suiteRunId.equals(attestation.suiteRunId())
                || !suiteRunId.equals(evidence.path("suiteRunId").asText())) {
            throw new IllegalArgumentException("Portable suite evidence bundle is incomplete");
        }
        evidence = evidence.deepCopy();
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Decodes a portable payload-free bundle against the authoritative schema.
     *
     * @param response decoded evidence bundle response
     * @return immutable typed bundle
     */
    public static TestSuiteEvidenceBundle from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "testSuiteEvidenceBundle");
        return new TestSuiteEvidenceBundle(response.path("suiteRunId").asText(),
                response.path("bundleFingerprint").asText(),
                enumValue(response.path("payloadPolicy").asText()),
                TestSuiteRunAttestation.from(response.path("attestation")),
                response.path("evidence"), response);
    }

    /**
     * Returns the aggregate evidence used for fingerprint verification.
     *
     * @return defensive copy of aggregate evidence
     */
    @Override
    public JsonNode evidence() {
        return evidence.deepCopy();
    }

    /**
     * Returns the complete schema-validated bundle used to reconstruct canonical material.
     *
     * @return defensive copy of the authorized complete bundle response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static PayloadPolicy enumValue(String value) {
        try {
            return PayloadPolicy.valueOf(normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown suite evidence payload policy");
        }
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
