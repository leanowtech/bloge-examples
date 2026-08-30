package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import org.junit.jupiter.api.TestInstance;

import java.time.Clock;

/** Concrete reference fixture for the reusable pending-secret contract. */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
final class InMemoryPendingSecretStoreContractTest extends PendingSecretStoreContractTest {
    @Override protected PendingSecretStore newStore(Clock clock) {
        return new InMemoryPendingSecretStore(clock);
    }
}
