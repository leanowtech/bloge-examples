package com.leanowtech.bloge.graphengine.service.command;

import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Command that updates mutable metadata on an existing graph definition.
 */
public record UpdateDefinitionCommand(
        String definitionId,
        long expectedRevision,
        String displayName,
        String description,
        GraphCategory category,
        Map<String, String> labels,
        String ownerTeam,
        RbacPolicy rbacPolicy
) {
    public UpdateDefinitionCommand {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }
        labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
        rbacPolicy = Objects.requireNonNullElse(rbacPolicy, new RbacPolicy(null, null, null, null));
    }
}
