package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GraphVersionStore} for tests and local development.
 */
public final class InMemoryGraphVersionStore implements GraphVersionStore {

    private final TimeSource timeSource;
    private final ConcurrentHashMap<String, GraphVersion> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> definitionVersionIndex = new ConcurrentHashMap<>();

    public InMemoryGraphVersionStore() {
        this(SystemTimeSource.INSTANCE);
    }

    /**
     * Creates the store with the supplied logical time source.
     *
     * @param timeSource time source used for timestamp generation
     */
    public InMemoryGraphVersionStore(TimeSource timeSource) {
        this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
    }

    @Override
    public synchronized void create(GraphVersion version) {
        Objects.requireNonNull(version, "version");
        if (versions.containsKey(version.versionId())) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.DUPLICATE,
                    "Graph version already exists: " + version.versionId()
            );
        }
        String key = key(version.definitionId(), version.version());
        if (definitionVersionIndex.containsKey(key)) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.DUPLICATE,
                    "Graph version already exists for definition '" + version.definitionId()
                            + "' version '" + version.version() + "'"
            );
        }
        versions.put(version.versionId(), version);
        definitionVersionIndex.put(key, version.versionId());
    }

    @Override
    public Optional<GraphVersion> get(String versionId) {
        return Optional.ofNullable(versions.get(versionId));
    }

    @Override
    public Optional<GraphVersion> getByDefinitionAndVersion(String definitionId, String version) {
        String versionId = definitionVersionIndex.get(key(definitionId, version));
        return versionId == null ? Optional.empty() : get(versionId);
    }

    @Override
    public List<GraphVersion> query(GraphVersionQuery query) {
        Objects.requireNonNull(query, "query");
        List<GraphVersion> filtered = versions.values().stream()
                .filter(version -> Objects.equals(query.definitionId(), version.definitionId()))
                .filter(version -> query.statuses().isEmpty() || query.statuses().contains(version.status()))
                .sorted(Comparator.comparing(GraphVersion::createdAt).reversed())
                .toList();
        return MemoryStoreSupport.slice(filtered, query.page(), query.size());
    }

    @Override
    public synchronized GraphVersion update(GraphVersion version, long expectedRevision) {
        Objects.requireNonNull(version, "version");
        GraphVersion existing = requireVersion(version.versionId());
        MemoryStoreSupport.requireExpectedRevision(
                "GraphVersion", version.versionId(), expectedRevision, existing.revision());

        String oldKey = key(existing.definitionId(), existing.version());
        String newKey = key(version.definitionId(), version.version());
        if (!oldKey.equals(newKey)) {
            String existingOwner = definitionVersionIndex.get(newKey);
            if (existingOwner != null && !existingOwner.equals(existing.versionId())) {
                throw new GraphEngineStoreException(
                        GraphEngineErrorCode.DUPLICATE,
                        "Graph version already exists for definition '" + version.definitionId()
                                + "' version '" + version.version() + "'"
                );
            }
            definitionVersionIndex.remove(oldKey);
            definitionVersionIndex.put(newKey, existing.versionId());
        }

        GraphVersion updated = new GraphVersion(
                existing.versionId(),
                version.definitionId(),
                version.version(),
                version.contentHash(),
                version.dslSource(),
                version.visualLayout(),
                version.metadata(),
                version.compiledArtifactRef(),
                version.migrationPolicy(),
                version.status(),
                existing.revision() + 1,
                version.publishedAt(),
                existing.createdAt(),
                timeSource.now()
        );
        versions.put(updated.versionId(), updated);
        return updated;
    }

    @Override
    public synchronized GraphVersion updateStatus(String versionId, GraphVersionStatus status, long expectedRevision) {
        GraphVersion existing = requireVersion(versionId);
        MemoryStoreSupport.requireExpectedRevision(
                "GraphVersion", versionId, expectedRevision, existing.revision());
        Instant now = timeSource.now();
        GraphVersion updated = new GraphVersion(
                existing.versionId(),
                existing.definitionId(),
                existing.version(),
                existing.contentHash(),
                existing.dslSource(),
                existing.visualLayout(),
                existing.metadata(),
                existing.compiledArtifactRef(),
                existing.migrationPolicy(),
                status,
                existing.revision() + 1,
                status == GraphVersionStatus.PUBLISHED
                        ? (existing.publishedAt() == null ? now : existing.publishedAt())
                        : existing.publishedAt(),
                existing.createdAt(),
                now
        );
        versions.put(versionId, updated);
        return updated;
    }

    private GraphVersion requireVersion(String versionId) {
        GraphVersion existing = versions.get(versionId);
        if (existing == null) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.NOT_FOUND,
                    "Graph version not found: " + versionId
            );
        }
        return existing;
    }

    @Override
    public Optional<GraphVersion> findLatestPublished(String definitionId) {
        return versions.values().stream()
                .filter(v -> Objects.equals(definitionId, v.definitionId()))
                .filter(v -> v.status() == GraphVersionStatus.PUBLISHED)
                .max(Comparator.comparing(GraphVersion::publishedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private static String key(String definitionId, String version) {
        return definitionId + '\u0000' + version;
    }
}
