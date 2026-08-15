package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.List;

/** Stable correctness API envelope whose identity scope is always server-derived. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessApiEnvelope<T>(
        String protocolVersion,
        String correlationId,
        List<String> capabilities,
        EnterpriseScope scope,
        T data
) {
    public static final String PROTOCOL_VERSION = "bloge.correctnessApi.v1";

    public CorrectnessApiEnvelope {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? PROTOCOL_VERSION : protocolVersion.trim();
        if (!PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("Unsupported correctness API protocolVersion");
        }
        correlationId = required(correlationId, "correlationId");
        capabilities = capabilities == null ? List.of() : capabilities.stream()
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().sorted().toList();
        if (scope == null || data == null) {
            throw new IllegalArgumentException("Correctness envelope scope and data are required");
        }
    }

    public static <T> CorrectnessApiEnvelope<T> of(
            String correlationId,
            EnterpriseScope scope,
            List<String> capabilities,
            T data
    ) {
        return new CorrectnessApiEnvelope<>("", correlationId, capabilities, scope, data);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
