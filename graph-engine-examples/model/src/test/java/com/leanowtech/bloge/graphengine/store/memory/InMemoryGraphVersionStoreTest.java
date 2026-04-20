package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.graphengine.store.GraphVersionStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphVersionStoreContract;

/**
 * Contract test suite for {@link InMemoryGraphVersionStore}.
 */
class InMemoryGraphVersionStoreTest extends GraphVersionStoreContract {
    @Override
    protected GraphVersionStore createStore() {
        return new InMemoryGraphVersionStore();
    }
}
