package com.leanowtech.bloge.graphengine.store.contract;

import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphInstanceStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.instance;
import static com.leanowtech.bloge.graphengine.store.contract.ContractTestSupport.tenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract for all {@link GraphInstanceStore} implementations.
 */
public abstract class GraphInstanceStoreContract {

    protected abstract GraphInstanceStore createStore();

    @Test
    void createGetAndQueryRoundTrip() {
        GraphInstanceStore store = createStore();
        store.create(instance("inst-1", "tenant-a", "ns-a", "orders", GraphInstanceStatus.RUNNING));

        assertEquals("orders", store.get("inst-1").orElseThrow().definitionKey());
        assertEquals(1, store.query(new GraphInstanceQuery(
                "tenant-a", "ns-a", "orders", null, Set.of(GraphInstanceStatus.RUNNING), GraphExecutionMode.GRAPH, 0, 10
        )).size());
    }

    @Test
    void updateTerminalStatusSetsCompletedAt() {
        GraphInstanceStore store = createStore();
        GraphInstance created = instance("inst-1", "tenant-a", "ns-a", "orders", GraphInstanceStatus.RUNNING);
        store.create(created);

        GraphInstance completed = store.update(new GraphInstance(
                created.instanceId(),
                created.definitionKey(),
                created.versionId(),
                created.tenantId(),
                created.namespace(),
                created.businessKey(),
                created.executionMode(),
                GraphInstanceStatus.COMPLETED,
                created.initiator(),
                Map.of("done", true),
                created.revision(),
                created.createdAt(),
                created.updatedAt(),
                null
        ), created.revision());

        assertEquals(1, completed.revision());
        assertNotNull(completed.completedAt());
    }

    @Test
    void versionMismatchIsRejected() {
        GraphInstanceStore store = createStore();
        GraphInstance created = instance("inst-1", "tenant-a", "ns-a", "orders", GraphInstanceStatus.RUNNING);
        store.create(created);

        GraphEngineStoreException error = assertThrows(GraphEngineStoreException.class,
                () -> store.update(created, 1));
        assertEquals(GraphEngineErrorCode.VERSION_CONFLICT, error.errorCode());
    }

    @Test
    void boundTenantIsolationHidesInstancesFromOtherTenants() throws Exception {
        GraphInstanceStore store = createStore();
        store.create(instance("inst-1", "tenant-a", "ns-a", "orders", GraphInstanceStatus.RUNNING));

        TenantContextHolder.callWith(tenant("tenant-b", "ns-b"), () -> {
            assertTrue(store.get("inst-1").isEmpty());
            assertTrue(store.query(new GraphInstanceQuery(null, null, null, null, Set.of(), null, 0, 10)).isEmpty());
            return null;
        });
    }

    @Test
    void updateStatusTransitionsAndBumpsRevision() {
        GraphInstanceStore store = createStore();
        store.create(instance("inst-status", "tenant-a", "ns-a", "orders",
                GraphInstanceStatus.RUNNING));

        GraphInstance completed = store.updateStatus(
                "inst-status", GraphInstanceStatus.COMPLETED, 0);
        assertEquals(GraphInstanceStatus.COMPLETED, completed.status());
        assertEquals(1, completed.revision());
        assertNotNull(completed.completedAt());
    }

    @Test
    void updateStatusThrowsOnRevisionMismatch() {
        GraphInstanceStore store = createStore();
        store.create(instance("inst-rev", "tenant-a", "ns-a", "orders",
                GraphInstanceStatus.RUNNING));

        GraphEngineStoreException error = assertThrows(
                GraphEngineStoreException.class,
                () -> store.updateStatus(
                        "inst-rev", GraphInstanceStatus.COMPLETED, 99));
        assertEquals(GraphEngineErrorCode.VERSION_CONFLICT, error.errorCode());
    }

    @Test
    void updateStatusThrowsForUnknownInstance() {
        GraphInstanceStore store = createStore();
        GraphEngineStoreException error = assertThrows(
                GraphEngineStoreException.class,
                () -> store.updateStatus(
                        "ghost", GraphInstanceStatus.COMPLETED, 0));
        assertEquals(GraphEngineErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void findByBusinessKeyReturnsMatchingInstance() {
        GraphInstanceStore store = createStore();
        store.create(instance("inst-bk", "tenant-a", "ns-a", "orders",
                GraphInstanceStatus.RUNNING));

        var found = store.findByBusinessKey(
                "tenant-a", "ns-a", "biz-inst-bk").orElseThrow();
        assertEquals("inst-bk", found.instanceId());
    }

    @Test
    void findByBusinessKeyReturnsEmptyWhenNotFound() {
        GraphInstanceStore store = createStore();
        assertTrue(store.findByBusinessKey(
                "tenant-a", "ns-a", "nonexistent").isEmpty());
    }

    @Test
    void duplicateInstanceIdIsRejected() {
        GraphInstanceStore store = createStore();
        store.create(instance("inst-dup", "tenant-a", "ns-a", "orders",
                GraphInstanceStatus.RUNNING));

        GraphEngineStoreException error = assertThrows(
                GraphEngineStoreException.class,
                () -> store.create(instance("inst-dup", "tenant-a", "ns-a",
                        "orders", GraphInstanceStatus.RUNNING)));
        assertEquals(GraphEngineErrorCode.DUPLICATE, error.errorCode());
    }
}
