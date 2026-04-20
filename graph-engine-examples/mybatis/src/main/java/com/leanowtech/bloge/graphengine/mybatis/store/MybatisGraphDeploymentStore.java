package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.GraphTenantSupport;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain MyBatis-backed {@link GraphDeploymentStore}.
 */
public final class MybatisGraphDeploymentStore
        extends AbstractGraphEngineStore<GraphDeploymentStoreMapper>
        implements GraphDeploymentStore {

    /**
     * Creates the store.
     *
     * @param sessionManager scoped MyBatis session manager
     * @param checkpointCodec durable JSON codec used for routing/operator-plane payloads
     * @param timeSource logical time source used for timestamp generation
     */
    public MybatisGraphDeploymentStore(ScopedSqlSessionManager sessionManager,
                                       CheckpointCodec checkpointCodec,
                                       TimeSource timeSource) {
        super(sessionManager, GraphDeploymentStoreMapper.class, checkpointCodec, timeSource);
    }

    @Override
    public void create(GraphDeployment deployment) {
        Objects.requireNonNull(deployment, "deployment");
        writeSession(session -> {
            GraphDeploymentStoreMapper mapper = getMapper(session, GraphDeploymentStoreMapper.class);
            if (mapper.selectById(deployment.deploymentId()) != null) {
                throw duplicate("Graph deployment", deployment.deploymentId());
            }
            if (deployment.active()) {
                mapper.deactivatePeers(
                        deployment.tenantId(),
                        deployment.namespace(),
                        deployment.definitionKey(),
                        deployment.environment(),
                        deployment.deploymentId(),
                        timeSource.now()
                );
            }
            mapper.insert(
                    deployment.deploymentId(),
                    deployment.definitionKey(),
                    deployment.tenantId(),
                    deployment.namespace(),
                    deployment.environment(),
                    VersionRoutingPolicyJsonCodec.encode(deployment.routingPolicy(), checkpointCodec),
                    OperatorPlaneConfigJsonCodec.encode(deployment.operatorPlaneConfig(), checkpointCodec),
                    deployment.active(),
                    deployment.revision(),
                    deployment.createdAt(),
                    deployment.updatedAt()
            );
            return null;
        });
    }

    @Override
    public Optional<GraphDeployment> get(String deploymentId) {
        return read(mapper -> Optional.ofNullable(mapDeployment(mapper.selectById(deploymentId)))
                .filter(deployment -> GraphTenantSupport.matchesCurrentTenant(
                        deployment.tenantId(), deployment.namespace())));
    }

    @Override
    public List<GraphDeployment> query(GraphDeploymentQuery query) {
        Objects.requireNonNull(query, "query");
        EffectiveScope scope = resolveScope(query.tenantId(), query.namespace());
        if (!scope.visible()) {
            return List.of();
        }
        return read(mapper -> mapper.query(
                        scope.tenantId(),
                        scope.namespace(),
                        query.definitionKey(),
                        query.environment(),
                        query.active(),
                        query.size(),
                        query.page() * query.size())
                .stream()
                .map(this::mapDeployment)
                .toList());
    }

    @Override
    public Optional<GraphDeployment> findActive(String tenantId, String namespace, String definitionKey, String environment) {
        EffectiveScope scope = resolveScope(tenantId, namespace);
        if (!scope.visible()) {
            return Optional.empty();
        }
        return read(mapper -> Optional.ofNullable(mapDeployment(
                mapper.selectActive(scope.tenantId(), scope.namespace(), definitionKey, environment))));
    }

    @Override
    public GraphDeployment update(GraphDeployment deployment, long expectedRevision) {
        Objects.requireNonNull(deployment, "deployment");
        return writeSession(session -> {
            GraphDeploymentStoreMapper mapper = getMapper(session, GraphDeploymentStoreMapper.class);
            GraphDeployment existing = mapDeployment(mapper.selectById(deployment.deploymentId()));
            if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
                throw notFound("Graph deployment", deployment.deploymentId());
            }
            if (deployment.active()) {
                mapper.deactivatePeers(
                        deployment.tenantId(),
                        deployment.namespace(),
                        deployment.definitionKey(),
                        deployment.environment(),
                        deployment.deploymentId(),
                        timeSource.now()
                );
            }
            int updated = mapper.update(
                    existing.deploymentId(),
                    deployment.definitionKey(),
                    deployment.tenantId(),
                    deployment.namespace(),
                    deployment.environment(),
                    VersionRoutingPolicyJsonCodec.encode(deployment.routingPolicy(), checkpointCodec),
                    OperatorPlaneConfigJsonCodec.encode(deployment.operatorPlaneConfig(), checkpointCodec),
                    deployment.active(),
                    expectedRevision,
                    timeSource.now()
            );
            if (updated == 0) {
                throw versionConflict("Graph deployment", deployment.deploymentId(), expectedRevision);
            }
            return mapDeployment(mapper.selectById(deployment.deploymentId()));
        });
    }

    @Override
    public GraphDeployment activate(String deploymentId, boolean active, long expectedRevision) {
        return writeSession(session -> {
            GraphDeploymentStoreMapper mapper = getMapper(session, GraphDeploymentStoreMapper.class);
            GraphDeployment existing = mapDeployment(mapper.selectById(deploymentId));
            if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
                throw notFound("Graph deployment", deploymentId);
            }
            if (active) {
                mapper.deactivatePeers(
                        existing.tenantId(),
                        existing.namespace(),
                        existing.definitionKey(),
                        existing.environment(),
                        deploymentId,
                        timeSource.now()
                );
            }
            int updated = mapper.updateActive(deploymentId, active, expectedRevision, timeSource.now());
            if (updated == 0) {
                throw versionConflict("Graph deployment", deploymentId, expectedRevision);
            }
            return mapDeployment(mapper.selectById(deploymentId));
        });
    }

    private GraphDeployment mapDeployment(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return new GraphDeployment(
                GraphEngineRowMapper.str(row, "deployment_id"),
                GraphEngineRowMapper.str(row, "definition_key"),
                GraphEngineRowMapper.str(row, "tenant_id"),
                GraphEngineRowMapper.str(row, "namespace"),
                GraphEngineRowMapper.str(row, "environment"),
                VersionRoutingPolicyJsonCodec.decode(GraphEngineRowMapper.str(row, "routing_policy_json"), checkpointCodec),
                OperatorPlaneConfigJsonCodec.decode(GraphEngineRowMapper.str(row, "operator_plane_json"), checkpointCodec),
                GraphEngineRowMapper.bool(row, "is_active"),
                GraphEngineRowMapper.lng(row, "revision", 0L),
                GraphEngineRowMapper.instant(row, "created_at"),
                GraphEngineRowMapper.instant(row, "updated_at")
        );
    }
}
