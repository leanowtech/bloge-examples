package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.graphengine.store.GraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphDeploymentStoreContract;

/**
 * Contract test suite for {@link InMemoryGraphDeploymentStore}.
 */
class InMemoryGraphDeploymentStoreTest extends GraphDeploymentStoreContract {
    @Override
    protected GraphDeploymentStore createStore() {
        return new InMemoryGraphDeploymentStore();
    }
}
