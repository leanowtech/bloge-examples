package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionQuery;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphTenantSupport;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain MyBatis-backed {@link GraphDefinitionStore}.
 */
public final class MybatisGraphDefinitionStore
        extends AbstractGraphEngineStore<GraphDefinitionStoreMapper>
        implements GraphDefinitionStore {

    /**
     * Creates the store.
     *
     * @param sessionManager scoped MyBatis session manager
     * @param checkpointCodec durable JSON codec used for structured metadata columns
     * @param timeSource logical time source used for timestamp generation
     */
    public MybatisGraphDefinitionStore(ScopedSqlSessionManager sessionManager,
                                       CheckpointCodec checkpointCodec,
                                       TimeSource timeSource) {
        super(sessionManager, GraphDefinitionStoreMapper.class, checkpointCodec, timeSource);
    }

    @Override
    public void create(GraphDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        writeSession(session -> {
            GraphDefinitionStoreMapper mapper = getMapper(session, GraphDefinitionStoreMapper.class);
            if (mapper.selectById(definition.definitionId()) != null) {
                throw duplicate("Graph definition", definition.definitionId());
            }
            if (mapper.selectByKey(definition.tenantId(), definition.namespace(), definition.definitionKey()) != null) {
                throw new GraphEngineStoreException(
                        GraphEngineErrorCode.DUPLICATE,
                        "Graph definition key already exists: " + definition.definitionKey()
                );
            }
            mapper.insert(
                    definition.definitionId(),
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    definition.displayName(),
                    definition.description(),
                    definition.category().name(),
                    GraphEngineJsonSupport.encode(checkpointCodec, definition.labels()),
                    definition.ownerTeam(),
                    RbacPolicyJsonCodec.encode(definition.rbacPolicy(), checkpointCodec),
                    definition.status().name(),
                    definition.revision(),
                    definition.createdAt(),
                    definition.updatedAt()
            );
            return null;
        });
    }

    @Override
    public Optional<GraphDefinition> get(String definitionId) {
        return read(mapper -> Optional.ofNullable(mapDefinition(mapper.selectById(definitionId)))
                .filter(definition -> GraphTenantSupport.matchesCurrentTenant(
                        definition.tenantId(), definition.namespace())));
    }

    @Override
    public Optional<GraphDefinition> getByKey(String tenantId, String namespace, String definitionKey) {
        EffectiveScope scope = resolveScope(tenantId, namespace);
        if (!scope.visible()) {
            return Optional.empty();
        }
        return read(mapper -> Optional.ofNullable(mapDefinition(
                mapper.selectByKey(scope.tenantId(), scope.namespace(), definitionKey))));
    }

    @Override
    public List<GraphDefinition> query(GraphDefinitionQuery query) {
        Objects.requireNonNull(query, "query");
        EffectiveScope scope = resolveScope(query.tenantId(), query.namespace());
        if (!scope.visible()) {
            return List.of();
        }
        return read(mapper -> mapper.query(
                        scope.tenantId(),
                        scope.namespace(),
                        query.status() == null ? null : query.status().name(),
                        query.definitionKey(),
                        query.ownerTeam(),
                        query.category() == null ? null : query.category().name(),
                        query.size(),
                        query.page() * query.size())
                .stream()
                .map(this::mapDefinition)
                .toList());
    }

    @Override
    public GraphDefinition update(GraphDefinition definition, long expectedRevision) {
        Objects.requireNonNull(definition, "definition");
        return writeSession(session -> {
            GraphDefinitionStoreMapper mapper = getMapper(session, GraphDefinitionStoreMapper.class);
            GraphDefinition existing = mapDefinition(mapper.selectById(definition.definitionId()));
            if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
                throw notFound("Graph definition", definition.definitionId());
            }
            if (!Objects.equals(existing.definitionKey(), definition.definitionKey())
                    || !Objects.equals(existing.tenantId(), definition.tenantId())
                    || !Objects.equals(existing.namespace(), definition.namespace())) {
                GraphDefinition owner = mapDefinition(
                        mapper.selectByKey(definition.tenantId(), definition.namespace(), definition.definitionKey()));
                if (owner != null && !Objects.equals(owner.definitionId(), existing.definitionId())) {
                    throw new GraphEngineStoreException(
                            GraphEngineErrorCode.DUPLICATE,
                            "Graph definition key already exists: " + definition.definitionKey()
                    );
                }
            }
            int updated = mapper.update(
                    existing.definitionId(),
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    definition.displayName(),
                    definition.description(),
                    definition.category().name(),
                    GraphEngineJsonSupport.encode(checkpointCodec, definition.labels()),
                    definition.ownerTeam(),
                    RbacPolicyJsonCodec.encode(definition.rbacPolicy(), checkpointCodec),
                    definition.status().name(),
                    expectedRevision,
                    timeSource.now()
            );
            if (updated == 0) {
                throw versionConflict("Graph definition", definition.definitionId(), expectedRevision);
            }
            return mapDefinition(mapper.selectById(definition.definitionId()));
        });
    }

    @Override
    public GraphDefinition archive(String definitionId, long expectedRevision) {
        return writeSession(session -> {
            GraphDefinitionStoreMapper mapper = getMapper(session, GraphDefinitionStoreMapper.class);
            GraphDefinition existing = mapDefinition(mapper.selectById(definitionId));
            if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
                throw notFound("Graph definition", definitionId);
            }
            int updated = mapper.updateStatus(
                    definitionId,
                    GraphDefinitionStatus.ARCHIVED.name(),
                    expectedRevision,
                    timeSource.now()
            );
            if (updated == 0) {
                throw versionConflict("Graph definition", definitionId, expectedRevision);
            }
            return mapDefinition(mapper.selectById(definitionId));
        });
    }

    private GraphDefinition mapDefinition(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        String category = GraphEngineRowMapper.str(row, "category");
        String status = GraphEngineRowMapper.str(row, "status");
        return new GraphDefinition(
                GraphEngineRowMapper.str(row, "definition_id"),
                GraphEngineRowMapper.str(row, "definition_key"),
                GraphEngineRowMapper.str(row, "tenant_id"),
                GraphEngineRowMapper.str(row, "namespace"),
                GraphEngineRowMapper.str(row, "display_name"),
                GraphEngineRowMapper.str(row, "description"),
                category == null ? GraphCategory.PIPELINE : GraphCategory.valueOf(category),
                GraphEngineJsonSupport.stringMap(GraphEngineJsonSupport.decodeMap(
                        checkpointCodec, GraphEngineRowMapper.str(row, "labels_json"))),
                GraphEngineRowMapper.str(row, "owner_team"),
                RbacPolicyJsonCodec.decode(GraphEngineRowMapper.str(row, "rbac_policy_json"), checkpointCodec),
                status == null ? GraphDefinitionStatus.ACTIVE : GraphDefinitionStatus.valueOf(status),
                GraphEngineRowMapper.lng(row, "revision", 0L),
                GraphEngineRowMapper.instant(row, "created_at"),
                GraphEngineRowMapper.instant(row, "updated_at")
        );
    }
}
