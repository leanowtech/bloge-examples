package com.leanowtech.bloge.graphengine.service.command;

import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Command that creates a new stable graph definition identity.
 */
public record CreateDefinitionCommand(
        String definitionKey,
        String tenantId,
        String namespace,
        String displayName,
        String description,
        GraphCategory category,
        Map<String, String> labels,
        String ownerTeam,
        RbacPolicy rbacPolicy
) {
    public CreateDefinitionCommand {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (tenantId != null && tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
        rbacPolicy = Objects.requireNonNullElse(rbacPolicy, new RbacPolicy(null, null, null, null));
    }
}
