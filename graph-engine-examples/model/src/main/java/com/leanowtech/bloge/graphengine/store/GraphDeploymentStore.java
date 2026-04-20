package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphDeployment;

import java.util.List;
import java.util.Optional;

/**
 * Store contract for graph deployment routing configuration.
 */
public interface GraphDeploymentStore {

    /**
     * Creates a new deployment.
     *
     * @param deployment deployment snapshot to persist
     */
    void create(GraphDeployment deployment);

    /**
     * Loads one deployment by identifier.
     *
     * @param deploymentId internal deployment identifier
     * @return matching deployment when present
     */
    Optional<GraphDeployment> get(String deploymentId);

    /**
     * Queries deployments matching the supplied filter.
     *
     * @param query query filter; must not be {@code null}
     * @return immutable page of deployments
     */
    List<GraphDeployment> query(GraphDeploymentQuery query);

    /**
     * Resolves the currently active deployment for one definition and environment.
     *
     * @param tenantId tenant identifier
     * @param namespace namespace identifier
     * @param definitionKey definition key
     * @param environment environment name
     * @return active deployment when one exists
     */
    Optional<GraphDeployment> findActive(String tenantId, String namespace, String definitionKey, String environment);

    /**
     * Replaces one deployment snapshot.
     *
     * @param deployment updated deployment
     * @param expectedRevision optimistic-lock revision guard
     * @return updated deployment snapshot
     */
    GraphDeployment update(GraphDeployment deployment, long expectedRevision);

    /**
     * Changes one deployment's active state.
     *
     * @param deploymentId deployment to mutate
     * @param active new active-state flag
     * @param expectedRevision optimistic-lock revision guard
     * @return updated deployment snapshot
     */
    GraphDeployment activate(String deploymentId, boolean active, long expectedRevision);
}
