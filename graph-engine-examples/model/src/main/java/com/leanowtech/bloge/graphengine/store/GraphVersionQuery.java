package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;

import java.util.Set;

/**
 * Query object for listing graph versions belonging to one definition.
 *
 * @param definitionId owning definition identifier
 * @param statuses accepted version states; empty means all states
 * @param page zero-based page index
 * @param size requested page size; non-positive values default to {@code 50}
 */
public record GraphVersionQuery(
        String definitionId,
        Set<GraphVersionStatus> statuses,
        int page,
        int size
) {
    public GraphVersionQuery {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        size = size <= 0 ? 50 : size;
    }
}
