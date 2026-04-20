package com.leanowtech.bloge.graphengine.service.command;

import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;

import java.util.Objects;

/**
 * Command that creates a new immutable version snapshot for one definition.
 */
public record CreateVersionCommand(
        String definitionKey,
        String tenantId,
        String namespace,
        String version,
        String dslSource,
        String visualLayout,
        GraphMigrationPolicy migrationPolicy
) {
    public CreateVersionCommand {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (tenantId != null && tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (dslSource == null || dslSource.isBlank()) {
            throw new IllegalArgumentException("dslSource must not be blank");
        }
        migrationPolicy = Objects.requireNonNullElse(migrationPolicy, GraphMigrationPolicy.PIN_VERSION);
    }
}
