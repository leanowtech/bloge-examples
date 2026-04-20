package com.leanowtech.bloge.graphengine.service.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command that starts a new product-layer instance from a published version.
 */
public record StartInstanceCommand(
        String definitionKey,
        String tenantId,
        String namespace,
        String version,
        String environment,
        String businessKey,
        String initiator,
        Map<String, Object> variables
) {
    public StartInstanceCommand {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (tenantId != null && tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (version != null && version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (environment != null && environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        if (businessKey != null && businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey must not be blank");
        }
        if (initiator != null && initiator.isBlank()) {
            throw new IllegalArgumentException("initiator must not be blank");
        }
        variables = variables == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variables));
    }
}
