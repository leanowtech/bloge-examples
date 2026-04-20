package com.leanowtech.bloge.graphengine.store.contract;

import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionQuery;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.definition;
import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.tenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Behavioral contract for all {@link GraphDefinitionStore} implementations.
 */
public abstract class GraphDefinitionStoreContract {

    protected abstract GraphDefinitionStore createStore();

    @Test
    void createGetAndGetByKeyRoundTrip() {
        GraphDefinitionStore store = createStore();
        GraphDefinition definition = definition("def-1", "tenant-a", "ns-a", "orders");
        store.create(definition);

        assertEquals("orders", store.get("def-1").orElseThrow().definitionKey());
        assertEquals("def-1", store.getByKey("tenant-a", "ns-a", "orders").orElseThrow().definitionId());
    }

    @Test
    void queryFiltersByStatusOwnerAndCategory() {
        GraphDefinitionStore store = createStore();
        store.create(definition("def-1", "tenant-a", "ns-a", "orders"));
        store.create(new GraphDefinition(
                "def-2", "refunds", "tenant-a", "ns-a", "Refunds", null,
                GraphCategory.APPROVAL, java.util.Map.of(), "ops", null,
                GraphDefinitionStatus.ARCHIVED, 0,
                ContractTestSupport.BASE_TIME, ContractTestSupport.BASE_TIME
        ));

        List<GraphDefinition> active = store.query(new GraphDefinitionQuery(
                "tenant-a", "ns-a", GraphDefinitionStatus.ACTIVE, null, "platform", GraphCategory.PIPELINE, 0, 10
        ));
        assertEquals(1, active.size());
        assertEquals("def-1", active.getFirst().definitionId());
    }

    @Test
    void updateIncrementsRevision() {
        GraphDefinitionStore store = createStore();
        GraphDefinition created = definition("def-1", "tenant-a", "ns-a", "orders");
        store.create(created);

        GraphDefinition updated = store.update(new GraphDefinition(
                "def-1", "orders", "tenant-a", "ns-a", "Orders V2", "updated",
                GraphCategory.PIPELINE, created.labels(), "platform", created.rbacPolicy(),
                GraphDefinitionStatus.ACTIVE, created.revision(), created.createdAt(), created.updatedAt()
        ), created.revision());

        assertEquals(1, updated.revision());
        assertEquals("Orders V2", updated.displayName());
    }

    @Test
    void archiveMarksDefinitionArchived() {
        GraphDefinitionStore store = createStore();
        GraphDefinition created = definition("def-1", "tenant-a", "ns-a", "orders");
        store.create(created);

        GraphDefinition archived = store.archive("def-1", created.revision());
        assertEquals(GraphDefinitionStatus.ARCHIVED, archived.status());
        assertEquals(1, archived.revision());
    }

    @Test
    void duplicateKeyIsRejected() {
        GraphDefinitionStore store = createStore();
        store.create(definition("def-1", "tenant-a", "ns-a", "orders"));

        GraphEngineStoreException error = assertThrows(GraphEngineStoreException.class,
                () -> store.create(definition("def-2", "tenant-a", "ns-a", "orders")));
        assertEquals(GraphEngineErrorCode.DUPLICATE, error.errorCode());
    }

    @Test
    void boundTenantIsolationHidesDefinitionsFromOtherTenants() throws Exception {
        GraphDefinitionStore store = createStore();
        store.create(definition("def-1", "tenant-a", "ns-a", "orders"));

        TenantContextHolder.callWith(tenant("tenant-b", "ns-b"), () -> {
            assertTrue(store.get("def-1").isEmpty());
            assertTrue(store.getByKey("tenant-a", "ns-a", "orders").isEmpty());
            assertTrue(store.query(new GraphDefinitionQuery(null, null, null, null, null, null, 0, 10)).isEmpty());
            return null;
        });
    }
}
