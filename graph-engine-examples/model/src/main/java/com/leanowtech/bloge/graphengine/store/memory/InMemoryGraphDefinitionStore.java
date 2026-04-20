package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionQuery;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionStore;
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
 * In-memory {@link GraphDefinitionStore} for tests and local development.
 */
public final class InMemoryGraphDefinitionStore implements GraphDefinitionStore {

    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, GraphDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> keyIndex = new ConcurrentHashMap<>();

    public InMemoryGraphDefinitionStore() {
        this(SystemTimeSource.INSTANCE);
    }

    /**
     * Creates the store with the supplied logical time source.
     *
     * @param timeSource time source used for timestamp generation
     */
    public InMemoryGraphDefinitionStore(TimeSource timeSource) {
        this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
    }

    @Override
    public synchronized void create(GraphDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definitions.containsKey(definition.definitionId())) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.DUPLICATE,
                    "Graph definition already exists: " + definition.definitionId()
            );
        }
        String key = key(definition.tenantId(), definition.namespace(), definition.definitionKey());
        if (keyIndex.containsKey(key)) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.DUPLICATE,
                    "Graph definition key already exists: " + definition.definitionKey()
            );
        }
        GraphDefinition stored = new GraphDefinition(
                definition.definitionId(),
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                definition.displayName(),
                definition.description(),
                definition.category(),
                definition.labels(),
                definition.ownerTeam(),
                definition.rbacPolicy(),
                definition.status(),
                definition.revision(),
                definition.createdAt(),
                definition.updatedAt()
        );
        definitions.put(stored.definitionId(), stored);
        keyIndex.put(key, stored.definitionId());
    }

    @Override
    public Optional<GraphDefinition> get(String definitionId) {
        return Optional.ofNullable(definitions.get(definitionId))
                .filter(definition -> GraphTenantSupport.matchesCurrentTenant(
                        definition.tenantId(), definition.namespace()));
    }

    @Override
    public Optional<GraphDefinition> getByKey(String tenantId, String namespace, String definitionKey) {
        String definitionId = keyIndex.get(key(tenantId, namespace, definitionKey));
        return definitionId == null ? Optional.empty() : get(definitionId);
    }

    @Override
    public List<GraphDefinition> query(GraphDefinitionQuery query) {
        Objects.requireNonNull(query, "query");
        List<GraphDefinition> filtered = definitions.values().stream()
                .filter(definition -> GraphTenantSupport.matchesCurrentTenant(
                        definition.tenantId(), definition.namespace()))
                .filter(definition -> query.tenantId() == null || Objects.equals(query.tenantId(), definition.tenantId()))
                .filter(definition -> query.namespace() == null || Objects.equals(query.namespace(), definition.namespace()))
                .filter(definition -> query.status() == null || definition.status() == query.status())
                .filter(definition -> query.definitionKey() == null || Objects.equals(query.definitionKey(), definition.definitionKey()))
                .filter(definition -> query.ownerTeam() == null || Objects.equals(query.ownerTeam(), definition.ownerTeam()))
                .filter(definition -> query.category() == null || definition.category() == query.category())
                .sorted(Comparator.comparing(GraphDefinition::updatedAt).reversed())
                .toList();
        return MemoryStoreSupport.slice(filtered, query.page(), query.size());
    }

    @Override
    public synchronized GraphDefinition update(GraphDefinition definition, long expectedRevision) {
        Objects.requireNonNull(definition, "definition");
        GraphDefinition existing = requireVisibleDefinition(definition.definitionId());
        MemoryStoreSupport.requireExpectedRevision(
                "GraphDefinition", definition.definitionId(), expectedRevision, existing.revision());

        String oldKey = key(existing.tenantId(), existing.namespace(), existing.definitionKey());
        String newKey = key(definition.tenantId(), definition.namespace(), definition.definitionKey());
        if (!oldKey.equals(newKey)) {
            String existingOwner = keyIndex.get(newKey);
            if (existingOwner != null && !existingOwner.equals(existing.definitionId())) {
                throw new GraphEngineStoreException(
                        GraphEngineErrorCode.DUPLICATE,
                        "Graph definition key already exists: " + definition.definitionKey()
                );
            }
            keyIndex.remove(oldKey);
            keyIndex.put(newKey, existing.definitionId());
        }

        Instant now = timeSource.now();
        GraphDefinition updated = new GraphDefinition(
                existing.definitionId(),
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                definition.displayName(),
                definition.description(),
                definition.category(),
                definition.labels(),
                definition.ownerTeam(),
                definition.rbacPolicy(),
                definition.status(),
                existing.revision() + 1,
                existing.createdAt(),
                now
        );
        definitions.put(updated.definitionId(), updated);
        return updated;
    }

    @Override
    public synchronized GraphDefinition archive(String definitionId, long expectedRevision) {
        GraphDefinition existing = requireVisibleDefinition(definitionId);
        MemoryStoreSupport.requireExpectedRevision(
                "GraphDefinition", definitionId, expectedRevision, existing.revision());
        GraphDefinition archived = new GraphDefinition(
                existing.definitionId(),
                existing.definitionKey(),
                existing.tenantId(),
                existing.namespace(),
                existing.displayName(),
                existing.description(),
                existing.category(),
                existing.labels(),
                existing.ownerTeam(),
                existing.rbacPolicy(),
                GraphDefinitionStatus.ARCHIVED,
                existing.revision() + 1,
                existing.createdAt(),
                timeSource.now()
        );
        definitions.put(definitionId, archived);
        return archived;
    }

    private GraphDefinition requireVisibleDefinition(String definitionId) {
        GraphDefinition existing = definitions.get(definitionId);
        if (existing == null || !GraphTenantSupport.matchesCurrentTenant(existing.tenantId(), existing.namespace())) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.NOT_FOUND,
                    "Graph definition not found: " + definitionId
            );
        }
        return existing;
    }

    private static String key(String tenantId, String namespace, String definitionKey) {
        return tenantId + '\u0000' + namespace + '\u0000' + definitionKey;
    }
}
