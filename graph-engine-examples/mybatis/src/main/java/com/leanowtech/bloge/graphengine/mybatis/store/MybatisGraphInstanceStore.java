package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;
import com.leanowtech.bloge.graphengine.store.GraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.GraphTenantSupport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain MyBatis-backed {@link GraphInstanceStore}.
 */
public final class MybatisGraphInstanceStore
        extends AbstractGraphEngineStore<GraphInstanceStoreMapper>
        implements GraphInstanceStore {

    /**
     * Creates the store.
     *
     * @param sessionManager scoped MyBatis session manager
     * @param checkpointCodec durable JSON codec used for instance variable payloads
     * @param timeSource logical time source used for timestamp generation
     */
    public MybatisGraphInstanceStore(ScopedSqlSessionManager sessionManager,
                                     CheckpointCodec checkpointCodec,
                                     TimeSource timeSource) {
        super(sessionManager, GraphInstanceStoreMapper.class, checkpointCodec, timeSource);
    }

    @Override
    public void create(GraphInstance instance) {
        Objects.requireNonNull(instance, "instance");
        writeSession(session -> {
            GraphInstanceStoreMapper mapper = getMapper(session, GraphInstanceStoreMapper.class);
            if (mapper.selectById(instance.instanceId()) != null) {
                throw duplicate("Graph instance", instance.instanceId());
            }
            mapper.insert(
                    instance.instanceId(),
                    instance.definitionKey(),
                    instance.versionId(),
                    instance.tenantId(),
                    instance.namespace(),
                    instance.businessKey(),
                    instance.executionMode().name(),
                    instance.status().name(),
                    instance.initiator(),
                    GraphEngineJsonSupport.encode(checkpointCodec, instance.variables()),
                    instance.revision(),
                    instance.createdAt(),
                    instance.updatedAt(),
                    instance.completedAt()
            );
            return null;
        });
    }

    @Override
    public Optional<GraphInstance> get(String instanceId) {
        return read(mapper -> Optional.ofNullable(mapInstance(mapper.selectById(instanceId)))
                .filter(instance -> GraphTenantSupport.matchesCurrentTenant(
                        instance.tenantId(), instance.namespace())));
    }

    @Override
    public List<GraphInstance> query(GraphInstanceQuery query) {
        Objects.requireNonNull(query, "query");
        EffectiveScope scope = resolveScope(query.tenantId(), query.namespace());
        if (!scope.visible()) {
            return List.of();
        }
        List<String> statuses = query.statuses().isEmpty()
                ? null
                : query.statuses().stream().map(GraphInstanceStatus::name).toList();
        return read(mapper -> mapper.query(
                        scope.tenantId(),
                        scope.namespace(),
                        query.definitionKey(),
                        query.businessKey(),
                        statuses,
                        query.executionMode() == null ? null : query.executionMode().name(),
                        query.size(),
                        query.page() * query.size())
                .stream()
                .map(this::mapInstance)
                .toList());
    }

    @Override
    public GraphInstance update(GraphInstance instance, long expectedRevision) {
        Objects.requireNonNull(instance, "instance");
        return writeSession(session -> {
            GraphInstanceStoreMapper mapper = getMapper(session, GraphInstanceStoreMapper.class);
            GraphInstance existing = mapInstance(mapper.selectById(instance.instanceId()));
            if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
                throw notFound("Graph instance", instance.instanceId());
            }
            Instant now = timeSource.now();
            Instant completedAt = instance.status().terminal()
                    ? (instance.completedAt() == null ? now : instance.completedAt())
                    : instance.completedAt();
            int updated = mapper.update(
                    existing.instanceId(),
                    instance.definitionKey(),
                    instance.versionId(),
                    instance.tenantId(),
                    instance.namespace(),
                    instance.businessKey(),
                    instance.executionMode().name(),
                    instance.status().name(),
                    instance.initiator(),
                    GraphEngineJsonSupport.encode(checkpointCodec, instance.variables()),
                    completedAt,
                    expectedRevision,
                    now
            );
            if (updated == 0) {
                throw versionConflict("Graph instance", instance.instanceId(), expectedRevision);
            }
            return mapInstance(mapper.selectById(instance.instanceId()));
        });
    }

    @Override
    public GraphInstance updateStatus(String instanceId, GraphInstanceStatus status, long expectedRevision) {
        return writeSession(session -> {
            GraphInstanceStoreMapper mapper = getMapper(session, GraphInstanceStoreMapper.class);
            GraphInstance existing = mapInstance(mapper.selectById(instanceId));
            if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
                throw notFound("Graph instance", instanceId);
            }
            Instant now = timeSource.now();
            Instant completedAt = status.terminal() ? now : existing.completedAt();
            int updated = mapper.updateStatus(instanceId, status.name(), completedAt, expectedRevision, now);
            if (updated == 0) {
                throw versionConflict("Graph instance", instanceId, expectedRevision);
            }
            return mapInstance(mapper.selectById(instanceId));
        });
    }

    @Override
    public Optional<GraphInstance> findByBusinessKey(String tenantId, String namespace, String businessKey) {
        EffectiveScope scope = resolveScope(tenantId, namespace);
        if (!scope.visible()) {
            return Optional.empty();
        }
        return read(mapper -> Optional.ofNullable(mapInstance(
                mapper.selectByBusinessKey(scope.tenantId(), scope.namespace(), businessKey))));
    }

    private GraphInstance mapInstance(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        String executionMode = GraphEngineRowMapper.str(row, "execution_mode");
        String status = GraphEngineRowMapper.str(row, "status");
        return new GraphInstance(
                GraphEngineRowMapper.str(row, "instance_id"),
                GraphEngineRowMapper.str(row, "definition_key"),
                GraphEngineRowMapper.str(row, "version_id"),
                GraphEngineRowMapper.str(row, "tenant_id"),
                GraphEngineRowMapper.str(row, "namespace"),
                GraphEngineRowMapper.str(row, "business_key"),
                executionMode == null ? GraphExecutionMode.GRAPH : GraphExecutionMode.valueOf(executionMode),
                status == null ? GraphInstanceStatus.RUNNING : GraphInstanceStatus.valueOf(status),
                GraphEngineRowMapper.str(row, "initiator"),
                GraphEngineJsonSupport.objectMap(GraphEngineJsonSupport.decodeMap(
                        checkpointCodec, GraphEngineRowMapper.str(row, "variables_json"))),
                GraphEngineRowMapper.lng(row, "revision", 0L),
                GraphEngineRowMapper.instant(row, "created_at"),
                GraphEngineRowMapper.instant(row, "updated_at"),
                GraphEngineRowMapper.instant(row, "completed_at")
        );
    }
}
