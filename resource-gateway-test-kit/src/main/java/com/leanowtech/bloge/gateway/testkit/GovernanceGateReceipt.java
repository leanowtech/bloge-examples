package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Schema-validated receipt for one immutable semantic governance gate decision.
 *
 * @param gateResultId immutable ANEKE decision id
 * @param status bounded gate status
 * @param resultFingerprint canonical decision fingerprint acknowledged by Resource Gateway
 * @param rawResponse defensive copy of the accepted gate result payload
 */
public record GovernanceGateReceipt(
        String gateResultId,
        String status,
        String resultFingerprint,
        JsonNode rawResponse
) {
    /** Validates and defensively copies a Tool Studio integration response. */
    public GovernanceGateReceipt {
        gateResultId = normalized(gateResultId);
        status = normalized(status);
        resultFingerprint = normalized(resultFingerprint);
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
        if (gateResultId.isBlank() || !resultFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Governance gate receipt identity is invalid");
        }
    }

    /**
     * Decodes the exact integration envelope and validates the v3 payload schema.
     *
     * @param envelope decoded Resource Gateway response
     * @return typed immutable receipt
     */
    public static GovernanceGateReceipt fromEnvelope(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()
                || !"ToolStudioResourceGatewayProtocol".equals(envelope.path("protocol").asText())
                || !"1.0".equals(envelope.path("protocolVersion").asText())
                || !"toolStudio.resourceGateway.envelope.v1".equals(
                envelope.path("schemaVersion").asText())
                || !"GOVERNANCE_GATE_RESULT".equals(envelope.path("payloadKind").asText())
                || !TestingProtocol.GOVERNANCE_GATE_RESULT_V3.equals(
                envelope.path("payloadSchemaVersion").asText())
                || !envelope.path("payloadFingerprint").asText()
                .matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Governance gate integration envelope is invalid");
        }
        JsonNode payload = envelope.path("payload");
        TestingProtocolSchemaValidator.requireRoot(payload,
                TestingProtocol.GOVERNANCE_GATE_V3_SCHEMA_RESOURCE);
        return new GovernanceGateReceipt(payload.path("gateResultId").asText(),
                payload.path("status").asText(), payload.path("resultFingerprint").asText(), payload);
    }

    /**
     * Returns a defensive copy of the schema-validated acknowledgement payload.
     *
     * @return copied v3 governance gate result payload, or {@code null} when absent
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
