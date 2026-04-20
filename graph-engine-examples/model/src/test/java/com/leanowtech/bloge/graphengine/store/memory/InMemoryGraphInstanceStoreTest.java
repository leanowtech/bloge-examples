package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.graphengine.store.GraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphInstanceStoreContract;

/**
 * Contract test suite for {@link InMemoryGraphInstanceStore}.
 */
class InMemoryGraphInstanceStoreTest extends GraphInstanceStoreContract {
    @Override
    protected GraphInstanceStore createStore() {
        return new InMemoryGraphInstanceStore();
    }
}
