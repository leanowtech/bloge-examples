package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;

import java.util.Set;

/**
 * Query object for listing product-layer instance projections.
 *
 * @param tenantId tenant filter; {@code null} means any tenant visible to the current scope
 * @param namespace namespace filter; {@code null} means any namespace visible to the current scope
 * @param definitionKey definition-key filter
 * @param businessKey business-key filter
 * @param statuses accepted instance states; empty means all states
 * @param executionMode execution-mode filter
 * @param page zero-based page index
 * @param size requested page size; non-positive values default to {@code 50}
 */
public record GraphInstanceQuery(
        String tenantId,
        String namespace,
        String definitionKey,
        String businessKey,
        Set<GraphInstanceStatus> statuses,
        GraphExecutionMode executionMode,
        int page,
        int size
) {
    public GraphInstanceQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        size = size <= 0 ? 50 : size;
    }
}
