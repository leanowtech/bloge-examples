package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;

import java.time.Clock;

/** Runs the reusable Connection commit-store contract against the model adapter. */
class InMemoryApiConnectionCommitStoreTest extends ApiConnectionCommitStoreContractTest {
    @Override
    protected ApiConnectionCommitStore createStore(Clock clock) {
        return new InMemoryApiConnectionCommitStore(clock, new ApiConnectionDecisions());
    }
}
