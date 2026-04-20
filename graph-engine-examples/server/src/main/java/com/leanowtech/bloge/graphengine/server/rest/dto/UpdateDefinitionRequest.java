package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

/**
 * HTTP payload that updates the mutable metadata of one graph definition.
 *
 * @param expectedRevision optimistic-lock revision expected by the caller
 * @param displayName human-readable display name
 * @param description optional description
 * @param category business category
 * @param labels free-form labels
 * @param ownerTeam owning team
 * @param rbacPolicy role-based access declaration
 */
public record UpdateDefinitionRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        String displayName,
        String description,
        GraphCategory category,
        Map<String, String> labels,
        String ownerTeam,
        RbacPolicy rbacPolicy
) {
}
