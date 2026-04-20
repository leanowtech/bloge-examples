package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Store contract for product-layer graph definition metadata.
 */
public interface GraphDefinitionStore {

    /**
     * Creates a new definition.
     *
     * @param definition definition to persist
     */
    void create(GraphDefinition definition);

    /**
     * Loads one definition by identifier.
     *
     * @param definitionId internal definition identifier
     * @return matching definition, when visible to the current tenant scope
     */
    Optional<GraphDefinition> get(String definitionId);

    /**
     * Loads one definition by business key.
     *
     * @param tenantId tenant identifier
     * @param namespace namespace identifier
     * @param definitionKey business-facing definition key
     * @return matching definition, when visible to the current tenant scope
     */
    Optional<GraphDefinition> getByKey(String tenantId, String namespace, String definitionKey);

    /**
     * Queries definitions matching the supplied filter.
     *
     * @param query query filter; must not be {@code null}
     * @return immutable page of definitions
     */
    List<GraphDefinition> query(GraphDefinitionQuery query);

    /**
     * Replaces one definition's mutable metadata.
     *
     * @param definition new definition snapshot
     * @param expectedRevision optimistic-lock revision guard
     * @return updated definition snapshot
     */
    GraphDefinition update(GraphDefinition definition, long expectedRevision);

    /**
     * Archives one definition.
     *
     * @param definitionId definition to archive
     * @param expectedRevision optimistic-lock revision guard
     * @return archived definition snapshot
     */
    GraphDefinition archive(String definitionId, long expectedRevision);
}
