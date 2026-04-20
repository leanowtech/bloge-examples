package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;

import java.util.List;
import java.util.Optional;

/**
 * Store contract for product-layer instance projections.
 */
public interface GraphInstanceStore {

    /**
     * Creates a new instance projection row.
     *
     * @param instance instance projection to persist
     */
    void create(GraphInstance instance);

    /**
     * Loads one instance projection by identifier.
     *
     * @param instanceId execution identifier
     * @return matching projection when present
     */
    Optional<GraphInstance> get(String instanceId);

    /**
     * Queries instance projections matching the supplied filter.
     *
     * @param query query filter; must not be {@code null}
     * @return immutable page of matching projections
     */
    List<GraphInstance> query(GraphInstanceQuery query);

    /**
     * Replaces one instance projection.
     *
     * @param instance updated projection snapshot
     * @param expectedRevision optimistic-lock revision guard
     * @return updated instance projection
     */
    GraphInstance update(GraphInstance instance, long expectedRevision);

    /**
     * Transitions an instance's lifecycle status, guarded by optimistic locking.
     *
     * @param instanceId instance identifier
     * @param status target lifecycle status
     * @param expectedRevision optimistic-lock revision guard
     * @return updated instance projection
     */
    GraphInstance updateStatus(String instanceId, GraphInstanceStatus status, long expectedRevision);

    /**
     * Finds an instance by its business key within a tenant scope.
     *
     * @param tenantId tenant identifier
     * @param namespace namespace
     * @param businessKey business correlation key
     * @return matching instance, or empty when not found
     */
    Optional<GraphInstance> findByBusinessKey(String tenantId, String namespace, String businessKey);
}
