package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphTenantSupport;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GraphDeploymentStore} for tests and local development.
 */
public final class InMemoryGraphDeploymentStore implements GraphDeploymentStore {

    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, GraphDeployment> deployments = new ConcurrentHashMap<>();

    public InMemoryGraphDeploymentStore() {
        this(SystemTimeSource.INSTANCE);
    }

    /**
     * Creates the store with the supplied logical time source.
     *
     * @param timeSource time source used for timestamp generation
     */
    public InMemoryGraphDeploymentStore(TimeSource timeSource) {
        this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
    }

    @Override
    public synchronized void create(GraphDeployment deployment) {
        Objects.requireNonNull(deployment, "deployment");
        if (deployments.containsKey(deployment.deploymentId())) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.DUPLICATE,
                    "Graph deployment already exists: " + deployment.deploymentId()
            );
        }
        if (deployment.active()) {
            deactivatePeers(deployment.tenantId(), deployment.namespace(), deployment.definitionKey(), deployment.environment(), null);
        }
        deployments.put(deployment.deploymentId(), deployment);
    }

    @Override
    public Optional<GraphDeployment> get(String deploymentId) {
        return Optional.ofNullable(deployments.get(deploymentId))
                .filter(deployment -> GraphTenantSupport.matchesCurrentTenant(
                        deployment.tenantId(), deployment.namespace()));
    }

    @Override
    public List<GraphDeployment> query(GraphDeploymentQuery query) {
        Objects.requireNonNull(query, "query");
        List<GraphDeployment> filtered = deployments.values().stream()
                .filter(deployment -> GraphTenantSupport.matchesCurrentTenant(
                        deployment.tenantId(), deployment.namespace()))
                .filter(deployment -> query.tenantId() == null || Objects.equals(query.tenantId(), deployment.tenantId()))
                .filter(deployment -> query.namespace() == null || Objects.equals(query.namespace(), deployment.namespace()))
                .filter(deployment -> query.definitionKey() == null || Objects.equals(query.definitionKey(), deployment.definitionKey()))
                .filter(deployment -> query.environment() == null || Objects.equals(query.environment(), deployment.environment()))
                .filter(deployment -> query.active() == null || query.active() == deployment.active())
                .sorted(Comparator.comparing(GraphDeployment::updatedAt).reversed())
                .toList();
        return MemoryStoreSupport.slice(filtered, query.page(), query.size());
    }

    @Override
    public Optional<GraphDeployment> findActive(String tenantId, String namespace, String definitionKey, String environment) {
        return deployments.values().stream()
                .filter(deployment -> GraphTenantSupport.matchesCurrentTenant(
                        deployment.tenantId(), deployment.namespace()))
                .filter(GraphDeployment::active)
                .filter(deployment -> Objects.equals(tenantId, deployment.tenantId()))
                .filter(deployment -> Objects.equals(namespace, deployment.namespace()))
                .filter(deployment -> Objects.equals(definitionKey, deployment.definitionKey()))
                .filter(deployment -> Objects.equals(environment, deployment.environment()))
                .max(Comparator.comparing(GraphDeployment::updatedAt));
    }

    @Override
    public synchronized GraphDeployment update(GraphDeployment deployment, long expectedRevision) {
        Objects.requireNonNull(deployment, "deployment");
        GraphDeployment existing = requireVisibleDeployment(deployment.deploymentId());
        MemoryStoreSupport.requireExpectedRevision(
                "GraphDeployment", deployment.deploymentId(), expectedRevision, existing.revision());
        if (deployment.active()) {
            deactivatePeers(deployment.tenantId(), deployment.namespace(),
                    deployment.definitionKey(), deployment.environment(),
                    deployment.deploymentId());
        }
        GraphDeployment updated = new GraphDeployment(
                existing.deploymentId(),
                deployment.definitionKey(),
                deployment.tenantId(),
                deployment.namespace(),
                deployment.environment(),
                deployment.routingPolicy(),
                deployment.operatorPlaneConfig(),
                deployment.active(),
                existing.revision() + 1,
                existing.createdAt(),
                timeSource.now()
        );
        deployments.put(updated.deploymentId(), updated);
        return updated;
    }

    @Override
    public synchronized GraphDeployment activate(String deploymentId, boolean active, long expectedRevision) {
        GraphDeployment existing = requireVisibleDeployment(deploymentId);
        MemoryStoreSupport.requireExpectedRevision(
                "GraphDeployment", deploymentId, expectedRevision, existing.revision());
        if (active) {
            deactivatePeers(existing.tenantId(), existing.namespace(), existing.definitionKey(), existing.environment(), deploymentId);
        }
        GraphDeployment updated = new GraphDeployment(
                existing.deploymentId(),
                existing.definitionKey(),
                existing.tenantId(),
                existing.namespace(),
                existing.environment(),
                existing.routingPolicy(),
                existing.operatorPlaneConfig(),
                active,
                existing.revision() + 1,
                existing.createdAt(),
                timeSource.now()
        );
        deployments.put(deploymentId, updated);
        return updated;
    }

    private GraphDeployment requireVisibleDeployment(String deploymentId) {
        GraphDeployment existing = deployments.get(deploymentId);
        if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.NOT_FOUND,
                    "Graph deployment not found: " + deploymentId
            );
        }
        return existing;
    }

    private void deactivatePeers(String tenantId, String namespace, String definitionKey, String environment, String exceptDeploymentId) {
        Instant now = timeSource.now();
        deployments.replaceAll((deploymentId, existing) -> {
            if (deploymentId.equals(exceptDeploymentId)) {
                return existing;
            }
            if (!Objects.equals(existing.tenantId(), tenantId)
                    || !Objects.equals(existing.namespace(), namespace)
                    || !Objects.equals(existing.definitionKey(), definitionKey)
                    || !Objects.equals(existing.environment(), environment)
                    || !existing.active()) {
                return existing;
            }
            return new GraphDeployment(
                    existing.deploymentId(),
                    existing.definitionKey(),
                    existing.tenantId(),
                    existing.namespace(),
                    existing.environment(),
                    existing.routingPolicy(),
                    existing.operatorPlaneConfig(),
                    false,
                    existing.revision() + 1,
                    existing.createdAt(),
                    now
            );
        });
    }
}
