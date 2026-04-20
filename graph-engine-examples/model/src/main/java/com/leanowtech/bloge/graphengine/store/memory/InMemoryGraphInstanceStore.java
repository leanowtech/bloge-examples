package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.GraphTenantSupport;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GraphInstanceStore} for tests and local development.
 */
public final class InMemoryGraphInstanceStore implements GraphInstanceStore {

    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, GraphInstance> instances = new ConcurrentHashMap<>();

    public InMemoryGraphInstanceStore() {
        this(SystemTimeSource.INSTANCE);
    }

    /**
     * Creates the store with the supplied logical time source.
     *
     * @param timeSource time source used for timestamp generation
     */
    public InMemoryGraphInstanceStore(TimeSource timeSource) {
        this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
    }

    @Override
    public synchronized void create(GraphInstance instance) {
        Objects.requireNonNull(instance, "instance");
        if (instances.putIfAbsent(instance.instanceId(), instance) != null) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.DUPLICATE,
                    "Graph instance already exists: " + instance.instanceId()
            );
        }
    }

    @Override
    public Optional<GraphInstance> get(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId))
                .filter(instance -> GraphTenantSupport.matchesCurrentTenant(
                        instance.tenantId(), instance.namespace()));
    }

    @Override
    public List<GraphInstance> query(GraphInstanceQuery query) {
        Objects.requireNonNull(query, "query");
        List<GraphInstance> filtered = instances.values().stream()
                .filter(instance -> GraphTenantSupport.matchesCurrentTenant(
                        instance.tenantId(), instance.namespace()))
                .filter(instance -> query.tenantId() == null || Objects.equals(query.tenantId(), instance.tenantId()))
                .filter(instance -> query.namespace() == null || Objects.equals(query.namespace(), instance.namespace()))
                .filter(instance -> query.definitionKey() == null || Objects.equals(query.definitionKey(), instance.definitionKey()))
                .filter(instance -> query.businessKey() == null || Objects.equals(query.businessKey(), instance.businessKey()))
                .filter(instance -> query.statuses().isEmpty() || query.statuses().contains(instance.status()))
                .filter(instance -> query.executionMode() == null || query.executionMode() == instance.executionMode())
                .sorted(Comparator.comparing(GraphInstance::updatedAt).reversed())
                .toList();
        return MemoryStoreSupport.slice(filtered, query.page(), query.size());
    }

    @Override
    public synchronized GraphInstance update(GraphInstance instance, long expectedRevision) {
        Objects.requireNonNull(instance, "instance");
        GraphInstance existing = requireVisibleInstance(instance.instanceId());
        MemoryStoreSupport.requireExpectedRevision(
                "GraphInstance", instance.instanceId(), expectedRevision, existing.revision());
        Instant now = timeSource.now();
        Instant completedAt = instance.status().terminal()
                ? (instance.completedAt() == null ? now : instance.completedAt())
                : instance.completedAt();
        GraphInstance updated = new GraphInstance(
                existing.instanceId(),
                instance.definitionKey(),
                instance.versionId(),
                instance.tenantId(),
                instance.namespace(),
                instance.businessKey(),
                instance.executionMode(),
                instance.status(),
                instance.initiator(),
                instance.variables(),
                existing.revision() + 1,
                existing.createdAt(),
                now,
                completedAt
        );
        instances.put(updated.instanceId(), updated);
        return updated;
    }

    private GraphInstance requireVisibleInstance(String instanceId) {
        GraphInstance existing = instances.get(instanceId);
        if (existing == null
                || !GraphTenantSupport.matchesCurrentTenant(
                        existing.tenantId(), existing.namespace())) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.NOT_FOUND,
                    "Graph instance not found: " + instanceId
            );
        }
        return existing;
    }

    @Override
    public synchronized GraphInstance updateStatus(
            String instanceId, GraphInstanceStatus status,
            long expectedRevision) {
        GraphInstance existing = requireVisibleInstance(instanceId);
        MemoryStoreSupport.requireExpectedRevision(
                "GraphInstance", instanceId,
                expectedRevision, existing.revision());
        Instant now = timeSource.now();
        Instant completedAt = status.terminal() ? now : existing.completedAt();
        GraphInstance updated = new GraphInstance(
                existing.instanceId(),
                existing.definitionKey(),
                existing.versionId(),
                existing.tenantId(),
                existing.namespace(),
                existing.businessKey(),
                existing.executionMode(),
                status,
                existing.initiator(),
                existing.variables(),
                existing.revision() + 1,
                existing.createdAt(),
                now,
                completedAt
        );
        instances.put(instanceId, updated);
        return updated;
    }

    @Override
    public Optional<GraphInstance> findByBusinessKey(
            String tenantId, String namespace, String businessKey) {
        return instances.values().stream()
                .filter(i -> GraphTenantSupport.matchesCurrentTenant(
                        i.tenantId(), i.namespace()))
                .filter(i -> Objects.equals(tenantId, i.tenantId()))
                .filter(i -> Objects.equals(namespace, i.namespace()))
                .filter(i -> Objects.equals(businessKey, i.businessKey()))
                .max(Comparator.comparing(GraphInstance::updatedAt));
    }
}
