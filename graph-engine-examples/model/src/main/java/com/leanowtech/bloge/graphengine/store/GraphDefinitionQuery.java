package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;

/**
 * Query object for listing graph definitions.
 *
 * @param tenantId tenant filter; {@code null} means any tenant visible to the current scope
 * @param namespace namespace filter; {@code null} means any namespace visible to the current scope
 * @param status definition status filter
 * @param definitionKey exact business key filter
 * @param ownerTeam owner-team filter
 * @param category business-category filter
 * @param page zero-based page index
 * @param size requested page size; non-positive values default to {@code 50}
 */
public record GraphDefinitionQuery(
        String tenantId,
        String namespace,
        GraphDefinitionStatus status,
        String definitionKey,
        String ownerTeam,
        GraphCategory category,
        int page,
        int size
) {
    public GraphDefinitionQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        size = size <= 0 ? 50 : size;
    }
}
