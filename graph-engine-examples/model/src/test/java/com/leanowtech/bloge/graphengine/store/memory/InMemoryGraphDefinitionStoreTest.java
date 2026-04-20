package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.graphengine.store.GraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphDefinitionStoreContract;

/**
 * Contract test suite for {@link InMemoryGraphDefinitionStore}.
 */
class InMemoryGraphDefinitionStoreTest extends GraphDefinitionStoreContract {
    @Override
    protected GraphDefinitionStore createStore() {
        return new InMemoryGraphDefinitionStore();
    }
}
