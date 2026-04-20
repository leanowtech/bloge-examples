package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;
import com.leanowtech.bloge.graphengine.model.RemoteWorkerBinding;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphDeploymentStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract test suite for {@link MybatisGraphDeploymentStore}.
 */
class MybatisGraphDeploymentStoreTest extends GraphDeploymentStoreContract {
    private final MybatisContractTestBase db = new MybatisContractTestBase();

    @BeforeEach
    void setUp() {
        db.setUp();
    }

    @Override
    protected GraphDeploymentStore createStore() {
        return new MybatisGraphDeploymentStore(db.sessionManager(), MybatisContractTestBase.CODEC, null);
    }

    @Test
    void roundTripsRoutingAndOperatorPlaneConfig() {
        GraphDeploymentStore store = createStore();
        GraphDeployment deployment = new GraphDeployment(
                "dep-rich",
                "orders",
                "tenant-a",
                "ns-a",
                "prod",
                new VersionRoutingPolicy.Canary("1.0.0", "1.1.0", 10),
                new OperatorPlaneConfig(
                        true,
                        List.of("libs/orders-plugin.jar"),
                        Map.of("customOp", new RemoteWorkerBinding(
                                "worker-orders",
                                "orders.topic",
                                "https://workers/orders",
                                Map.of("region", "ap-southeast")
                        ))
                ),
                true,
                0,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-01T00:00:00Z")
        );
        store.create(deployment);

        GraphDeployment loaded = store.get("dep-rich").orElseThrow();
        assertEquals(10, ((VersionRoutingPolicy.Canary) loaded.routingPolicy()).percentage());
        assertEquals("worker-orders", loaded.operatorPlaneConfig().remoteWorkers().get("customOp").workerId());
    }
}
