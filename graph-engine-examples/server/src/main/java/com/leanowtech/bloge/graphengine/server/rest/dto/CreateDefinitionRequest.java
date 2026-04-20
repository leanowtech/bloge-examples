package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * HTTP payload that creates one stable graph definition identity.
 *
 * @param definitionKey business-facing definition key
 * @param displayName human-readable display name
 * @param description optional description
 * @param category business category
 * @param labels free-form labels
 * @param ownerTeam owning team
 * @param rbacPolicy role-based access declaration
 */
public record CreateDefinitionRequest(
        @NotBlank String definitionKey,
        String displayName,
        String description,
        GraphCategory category,
        Map<String, String> labels,
        String ownerTeam,
        RbacPolicy rbacPolicy
) {
}
