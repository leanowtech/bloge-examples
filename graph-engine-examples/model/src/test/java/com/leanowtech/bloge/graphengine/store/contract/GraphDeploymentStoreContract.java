package com.leanowtech.bloge.graphengine.store.contract;

import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import org.junit.jupiter.api.Test;

import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.deployment;
import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.tenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract for all {@link GraphDeploymentStore} implementations.
 */
public abstract class GraphDeploymentStoreContract {

    protected abstract GraphDeploymentStore createStore();

    @Test
    void createGetAndFindActiveRoundTrip() {
        GraphDeploymentStore store = createStore();
        store.create(deployment("dep-1", "tenant-a", "ns-a", "orders", "prod", false));

        GraphDeployment activated = store.activate("dep-1", true, 0);
        assertTrue(activated.active());
        assertEquals("dep-1", store.findActive("tenant-a", "ns-a", "orders", "prod").orElseThrow().deploymentId());
    }

    @Test
    void activatingOneDeploymentDeactivatesPeers() {
        GraphDeploymentStore store = createStore();
        store.create(deployment("dep-1", "tenant-a", "ns-a", "orders", "prod", true));
        store.create(deployment("dep-2", "tenant-a", "ns-a", "orders", "prod", false));

        GraphDeployment activated = store.activate("dep-2", true, 0);
        assertTrue(activated.active());
        assertFalse(store.get("dep-1").orElseThrow().active());
    }

    @Test
    void queryFiltersByEnvironmentAndActive() {
        GraphDeploymentStore store = createStore();
        store.create(deployment("dep-1", "tenant-a", "ns-a", "orders", "prod", true));
        store.create(deployment("dep-2", "tenant-a", "ns-a", "orders", "staging", false));

        assertEquals(1, store.query(new GraphDeploymentQuery("tenant-a", "ns-a", "orders", "prod", true, 0, 10)).size());
    }

    @Test
    void versionMismatchIsRejected() {
        GraphDeploymentStore store = createStore();
        store.create(deployment("dep-1", "tenant-a", "ns-a", "orders", "prod", false));

        GraphEngineStoreException error = assertThrows(GraphEngineStoreException.class,
                () -> store.activate("dep-1", true, 1));
        assertEquals(GraphEngineErrorCode.VERSION_CONFLICT, error.errorCode());
    }

    @Test
    void boundTenantIsolationHidesDeploymentsFromOtherTenants() throws Exception {
        GraphDeploymentStore store = createStore();
        store.create(deployment("dep-1", "tenant-a", "ns-a", "orders", "prod", true));

        TenantContextHolder.callWith(tenant("tenant-b", "ns-b"), () -> {
            assertTrue(store.get("dep-1").isEmpty());
            assertTrue(store.findActive("tenant-a", "ns-a", "orders", "prod").isEmpty());
            assertTrue(store.query(new GraphDeploymentQuery(null, null, null, null, null, 0, 10)).isEmpty());
            return null;
        });
    }
}
