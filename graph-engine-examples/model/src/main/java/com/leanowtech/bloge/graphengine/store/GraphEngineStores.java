package com.leanowtech.bloge.graphengine.store;

/**
 * Aggregate of the product-layer metadata stores needed by the graph-engine
 * service layer.
 *
 * @param graphDefinitionStore definition metadata store
 * @param graphVersionStore version metadata store
 * @param graphDeploymentStore deployment routing store
 * @param graphInstanceStore instance projection store
 */
public record GraphEngineStores(
        GraphDefinitionStore graphDefinitionStore,
        GraphVersionStore graphVersionStore,
        GraphDeploymentStore graphDeploymentStore,
        GraphInstanceStore graphInstanceStore
) {
}
