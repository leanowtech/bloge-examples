package com.leanowtech.bloge.graphengine.store;

/**
 * Query object for listing graph deployments.
 *
 * @param tenantId tenant filter; {@code null} means any tenant visible to the current scope
 * @param namespace namespace filter; {@code null} means any namespace visible to the current scope
 * @param definitionKey definition-key filter
 * @param environment environment filter
 * @param active active-state filter
 * @param page zero-based page index
 * @param size requested page size; non-positive values default to {@code 50}
 */
public record GraphDeploymentQuery(
        String tenantId,
        String namespace,
        String definitionKey,
        String environment,
        Boolean active,
        int page,
        int size
) {
    public GraphDeploymentQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        size = size <= 0 ? 50 : size;
    }
}
