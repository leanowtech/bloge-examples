package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Versioned transport envelope for all Tool Studio integration payloads.
 */
public record IntegrationEnvelope<T>(
        String protocol,
        String protocolVersion,
        String resourceGatewayVersion,
        String schemaVersion,
        Instant producedAt,
        Compatibility compatibility,
        String payloadKind,
        String payloadSchemaVersion,
        String payloadFingerprint,
        T payload
) {
    public IntegrationEnvelope {
        protocol = protocol == null || protocol.isBlank() ? ToolStudioResourceGatewayProtocol.NAME : protocol;
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? ToolStudioResourceGatewayProtocol.VERSION : protocolVersion;
        resourceGatewayVersion = resourceGatewayVersion == null || resourceGatewayVersion.isBlank()
                ? ToolStudioResourceGatewayProtocol.RESOURCE_GATEWAY_VERSION : resourceGatewayVersion;
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? ToolStudioResourceGatewayProtocol.ENVELOPE_SCHEMA_VERSION : schemaVersion;
        producedAt = producedAt == null ? Instant.now() : producedAt;
        compatibility = compatibility == null ? Compatibility.current() : compatibility;
        payloadKind = payloadKind == null ? "" : payloadKind.trim().toUpperCase();
        payloadSchemaVersion = payloadSchemaVersion == null ? "" : payloadSchemaVersion;
        payloadFingerprint = payloadFingerprint == null || payloadFingerprint.isBlank()
                ? fingerprint(payloadKind, payloadSchemaVersion, payload)
                : payloadFingerprint;
    }

    public static <T> IntegrationEnvelope<T> of(String payloadKind,
                                                String payloadSchemaVersion,
                                                T payload) {
        return new IntegrationEnvelope<>("", "", "", "", null, null,
                payloadKind, payloadSchemaVersion, "", payload);
    }

    private static String fingerprint(String payloadKind, String payloadSchemaVersion, Object payload) {
        return VisualBundleFingerprint.fromMaterial(Map.of(
                "payloadKind", payloadKind,
                "payloadSchemaVersion", payloadSchemaVersion,
                "payload", payload
        ));
    }

    /**
     * Compatibility statement for this protocol producer.
     */
    public record Compatibility(
            String minConsumerVersion,
            boolean backwardCompatible,
            List<String> breakingChanges
    ) {
        public Compatibility {
            minConsumerVersion = minConsumerVersion == null || minConsumerVersion.isBlank()
                    ? ToolStudioResourceGatewayProtocol.VERSION : minConsumerVersion;
            breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
        }

        public static Compatibility current() {
            return new Compatibility(ToolStudioResourceGatewayProtocol.VERSION, true, List.of());
        }
    }
}
