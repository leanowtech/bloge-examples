package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.mybatis.session.ScopedSqlSessionManager;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;
import com.leanowtech.bloge.graphengine.store.GraphVersionStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain MyBatis-backed {@link GraphVersionStore}.
 */
public final class MybatisGraphVersionStore
        extends AbstractGraphEngineStore<GraphVersionStoreMapper>
        implements GraphVersionStore {

    /**
     * Creates the store.
     *
     * @param sessionManager scoped MyBatis session manager
     * @param checkpointCodec durable JSON codec used for metadata payloads
     * @param timeSource logical time source used for timestamp generation
     */
    public MybatisGraphVersionStore(ScopedSqlSessionManager sessionManager,
                                    CheckpointCodec checkpointCodec,
                                    TimeSource timeSource) {
        super(sessionManager, GraphVersionStoreMapper.class, checkpointCodec, timeSource);
    }

    @Override
    public void create(GraphVersion version) {
        Objects.requireNonNull(version, "version");
        writeSession(session -> {
            GraphVersionStoreMapper mapper = getMapper(session, GraphVersionStoreMapper.class);
            if (mapper.selectById(version.versionId()) != null) {
                throw duplicate("Graph version", version.versionId());
            }
            if (mapper.selectByDefinitionAndVersion(version.definitionId(), version.version()) != null) {
                throw new GraphEngineStoreException(
                        GraphEngineErrorCode.DUPLICATE,
                        "Graph version already exists for definition '" + version.definitionId()
                                + "' version '" + version.version() + '\''
                );
            }
            mapper.insert(
                    version.versionId(),
                    version.definitionId(),
                    version.version(),
                    version.contentHash(),
                    version.dslSource(),
                    version.visualLayout(),
                    GraphVersionMetadataJsonCodec.encode(version.metadata(), checkpointCodec),
                    version.compiledArtifactRef(),
                    version.migrationPolicy().name(),
                    version.status().name(),
                    version.revision(),
                    version.publishedAt(),
                    version.createdAt(),
                    version.updatedAt()
            );
            return null;
        });
    }

    @Override
    public Optional<GraphVersion> get(String versionId) {
        return read(mapper -> Optional.ofNullable(mapVersion(mapper.selectById(versionId))));
    }

    @Override
    public Optional<GraphVersion> getByDefinitionAndVersion(String definitionId, String version) {
        return read(mapper -> Optional.ofNullable(mapVersion(
                mapper.selectByDefinitionAndVersion(definitionId, version))));
    }

    @Override
    public List<GraphVersion> query(GraphVersionQuery query) {
        Objects.requireNonNull(query, "query");
        List<String> statuses = query.statuses().isEmpty()
                ? null
                : query.statuses().stream().map(GraphVersionStatus::name).toList();
        return read(mapper -> mapper.query(query.definitionId(), statuses, query.size(), query.page() * query.size())
                .stream()
                .map(this::mapVersion)
                .toList());
    }

    @Override
    public GraphVersion update(GraphVersion version, long expectedRevision) {
        Objects.requireNonNull(version, "version");
        return writeSession(session -> {
            GraphVersionStoreMapper mapper = getMapper(session, GraphVersionStoreMapper.class);
            GraphVersion existing = mapVersion(mapper.selectById(version.versionId()));
            if (existing == null) {
                throw notFound("Graph version", version.versionId());
            }
            if (!Objects.equals(existing.definitionId(), version.definitionId())
                    || !Objects.equals(existing.version(), version.version())) {
                GraphVersion owner = mapVersion(mapper.selectByDefinitionAndVersion(version.definitionId(), version.version()));
                if (owner != null && !Objects.equals(owner.versionId(), existing.versionId())) {
                    throw new GraphEngineStoreException(
                            GraphEngineErrorCode.DUPLICATE,
                            "Graph version already exists for definition '" + version.definitionId()
                                    + "' version '" + version.version() + '\''
                    );
                }
            }
            int updated = mapper.update(
                    existing.versionId(),
                    version.definitionId(),
                    version.version(),
                    version.contentHash(),
                    version.dslSource(),
                    version.visualLayout(),
                    GraphVersionMetadataJsonCodec.encode(version.metadata(), checkpointCodec),
                    version.compiledArtifactRef(),
                    version.migrationPolicy().name(),
                    version.status().name(),
                    version.publishedAt(),
                    expectedRevision,
                    timeSource.now()
            );
            if (updated == 0) {
                throw versionConflict("Graph version", version.versionId(), expectedRevision);
            }
            return mapVersion(mapper.selectById(version.versionId()));
        });
    }

    @Override
    public GraphVersion updateStatus(String versionId, GraphVersionStatus status, long expectedRevision) {
        return writeSession(session -> {
            GraphVersionStoreMapper mapper = getMapper(session, GraphVersionStoreMapper.class);
            GraphVersion existing = mapVersion(mapper.selectById(versionId));
            if (existing == null) {
                throw notFound("Graph version", versionId);
            }
            Instant now = timeSource.now();
            Instant publishedAt = status == GraphVersionStatus.PUBLISHED
                    ? (existing.publishedAt() == null ? now : existing.publishedAt())
                    : existing.publishedAt();
            int updated = mapper.updateStatus(
                    versionId,
                    status.name(),
                    publishedAt,
                    expectedRevision,
                    now
            );
            if (updated == 0) {
                throw versionConflict("Graph version", versionId, expectedRevision);
            }
            return mapVersion(mapper.selectById(versionId));
        });
    }

    @Override
    public Optional<GraphVersion> findLatestPublished(String definitionId) {
        return read(mapper -> Optional.ofNullable(mapVersion(mapper.selectLatestPublished(definitionId))));
    }

    private GraphVersion mapVersion(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        String migrationPolicy = GraphEngineRowMapper.str(row, "migration_policy");
        String status = GraphEngineRowMapper.str(row, "status");
        return new GraphVersion(
                GraphEngineRowMapper.str(row, "version_id"),
                GraphEngineRowMapper.str(row, "definition_id"),
                GraphEngineRowMapper.str(row, "version"),
                GraphEngineRowMapper.str(row, "content_hash"),
                GraphEngineRowMapper.str(row, "dsl_source"),
                GraphEngineRowMapper.str(row, "visual_layout"),
                GraphVersionMetadataJsonCodec.decode(GraphEngineRowMapper.str(row, "metadata_json"), checkpointCodec),
                GraphEngineRowMapper.str(row, "compiled_artifact_ref"),
                migrationPolicy == null ? GraphMigrationPolicy.PIN_VERSION : GraphMigrationPolicy.valueOf(migrationPolicy),
                status == null ? GraphVersionStatus.DRAFT : GraphVersionStatus.valueOf(status),
                GraphEngineRowMapper.lng(row, "revision", 0L),
                GraphEngineRowMapper.instant(row, "published_at"),
                GraphEngineRowMapper.instant(row, "created_at"),
                GraphEngineRowMapper.instant(row, "updated_at")
        );
    }
}
