package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.graphengine.store.GraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.contract.GraphDefinitionStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.definition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test suite for {@link MybatisGraphDefinitionStore}.
 */
class MybatisGraphDefinitionStoreTest extends GraphDefinitionStoreContract {
    private final MybatisContractTestBase db = new MybatisContractTestBase();

    @BeforeEach
    void setUp() {
        db.setUp();
    }

    @Override
    protected GraphDefinitionStore createStore() {
        return new MybatisGraphDefinitionStore(db.sessionManager(), MybatisContractTestBase.CODEC, null);
    }

    @Test
    void roundTripsLabelsAndRbacPolicy() {
        GraphDefinitionStore store = createStore();
        store.create(definition("def-rich", "tenant-a", "ns-a", "orders-rich"));

        var loaded = store.get("def-rich").orElseThrow();
        assertEquals("platform", loaded.ownerTeam());
        assertEquals("platform", loaded.labels().get("team"));
        assertTrue(loaded.rbacPolicy().viewRoles().isEmpty());
    }
}
