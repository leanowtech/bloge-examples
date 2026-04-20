package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.store.GraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphInstanceStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract test suite for {@link MybatisGraphInstanceStore}.
 */
class MybatisGraphInstanceStoreTest extends GraphInstanceStoreContract {
    private final MybatisContractTestBase db = new MybatisContractTestBase();

    @BeforeEach
    void setUp() {
        db.setUp();
    }

    @Override
    protected GraphInstanceStore createStore() {
        return new MybatisGraphInstanceStore(db.sessionManager(), MybatisContractTestBase.CODEC, null);
    }

    @Test
    void roundTripsVariablesAndBusinessKeyLookup() {
        GraphInstanceStore store = createStore();
        GraphInstance instance = new GraphInstance(
                "inst-rich",
                "orders",
                "ver-1",
                "tenant-a",
                "ns-a",
                "biz-rich",
                GraphExecutionMode.STATE_MACHINE,
                GraphInstanceStatus.RUNNING,
                "tester",
                Map.of("amount", 42, "approved", true),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null
        );
        store.create(instance);

        GraphInstance loaded = store.findByBusinessKey("tenant-a", "ns-a", "biz-rich").orElseThrow();
        assertEquals(GraphExecutionMode.STATE_MACHINE, loaded.executionMode());
        assertEquals(42, ((Number) loaded.variables().get("amount")).intValue());
    }
}
