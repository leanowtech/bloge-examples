package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed graph draft repository with revision assignment and cache-backed reads.
 */
public class DatabaseGraphDraftRepository implements GraphDraftRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseGraphDraftRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_drafts (
                draft_id VARCHAR(255) PRIMARY KEY,
                revision BIGINT NOT NULL,
                draft_json CLOB NOT NULL
            )
            """;
    private static final String CREATE_REVISION_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_draft_revisions (
                draft_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                draft_json CLOB NOT NULL,
                PRIMARY KEY (draft_id, revision)
            )
            """;

    private static final String SELECT_ALL = "SELECT draft_id, draft_json FROM visual_graph_drafts";
    private static final String SELECT_REVISIONS = """
            SELECT draft_json
            FROM visual_graph_draft_revisions
            WHERE draft_id = ?
            ORDER BY revision DESC
            """;
    private static final String SELECT_REVISION = """
            SELECT draft_json
            FROM visual_graph_draft_revisions
            WHERE draft_id = ? AND revision = ?
            """;
    private static final String UPSERT = "MERGE INTO visual_graph_drafts (draft_id, revision, draft_json) KEY (draft_id) VALUES (?, ?, ?)";
    private static final String UPSERT_REVISION = """
            MERGE INTO visual_graph_draft_revisions (draft_id, revision, draft_json)
            KEY (draft_id, revision)
            VALUES (?, ?, ?)
            """;
    private static final String UPDATE_IF_REVISION = """
            UPDATE visual_graph_drafts
            SET revision = ?, draft_json = ?
            WHERE draft_id = ? AND revision = ?
            """;
    private static final String DELETE = "DELETE FROM visual_graph_drafts WHERE draft_id = ?";
    private static final String DELETE_REVISIONS = "DELETE FROM visual_graph_draft_revisions WHERE draft_id = ?";

    private final ConcurrentHashMap<String, GraphDraft> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseGraphDraftRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Initializes table and cache.
     */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.execute(CREATE_REVISION_TABLE);
        cache.clear();
        jdbc.query(SELECT_ALL, (rs, rowNum) -> {
            String draftId = rs.getString("draft_id");
            String json = rs.getString("draft_json");
            try {
                GraphDraft draft = objectMapper.readValue(json, GraphDraft.class);
                cache.put(draftId, draft);
                log.info("Loaded visual graph draft from DB: {}", draftId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual graph draft '{}': {}", draftId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseGraphDraftRepository initialized with {} drafts", cache.size());
    }

    @Override
    public Collection<GraphDraft> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(GraphDraft::draftId))
                .toList();
    }

    @Override
    public Optional<GraphDraft> find(String draftId) {
        return Optional.ofNullable(cache.get(draftId));
    }

    @Override
    public List<GraphDraft> revisions(String draftId) {
        return jdbc.query(SELECT_REVISIONS, (rs, rowNum) -> readDraft(rs.getString("draft_json"), draftId),
                draftId).stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<GraphDraft> findRevision(String draftId, long revision) {
        return jdbc.query(SELECT_REVISION, (rs, rowNum) -> readDraft(rs.getString("draft_json"), draftId),
                draftId, revision).stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public GraphDraft save(GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        String draftId = draft.draftId().isBlank() ? UUID.randomUUID().toString() : draft.draftId();
        GraphDraft current = cache.get(draftId);
        long currentRevision = current == null ? 0 : current.revision();
        long nextRevision = Math.max(draft.revision(), currentRevision) + 1;
        GraphDraft stored = draft.withIdentity(draftId, nextRevision)
                .withRevisionMetadata(draft.revisionMetadata().storedFrom(
                        current == null ? null : current.revisionMetadata(), "Saved draft."));
        persist(stored);
        cache.put(draftId, stored);
        log.info("Saved visual graph draft: {}@{}", draftId, nextRevision);
        return stored;
    }

    @Override
    public Optional<GraphDraft> saveIfRevision(String draftId, long expectedRevision, GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        GraphDraft current = cache.get(draftId);
        GraphDraft stored = draft.withIdentity(draftId, expectedRevision + 1)
                .withRevisionMetadata(draft.revisionMetadata().storedFrom(
                        current == null ? null : current.revisionMetadata(), "Patched draft."));
        int updated;
        String json;
        try {
            json = objectMapper.writeValueAsString(stored);
            updated = jdbc.update(UPDATE_IF_REVISION, stored.revision(), json, draftId, expectedRevision);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph draft: " + draftId, e);
        }
        if (updated == 0) {
            return Optional.empty();
        }
        jdbc.update(UPSERT_REVISION, stored.draftId(), stored.revision(), json);
        cache.put(draftId, stored);
        log.info("Patched visual graph draft: {}@{}", draftId, stored.revision());
        return Optional.of(stored);
    }

    @Override
    public void delete(String draftId) {
        jdbc.update(DELETE, draftId);
        jdbc.update(DELETE_REVISIONS, draftId);
        cache.remove(draftId);
        log.info("Deleted visual graph draft: {}", draftId);
    }

    private void persist(GraphDraft draft) {
        try {
            String json = objectMapper.writeValueAsString(draft);
            jdbc.update(UPSERT, draft.draftId(), draft.revision(), json);
            jdbc.update(UPSERT_REVISION, draft.draftId(), draft.revision(), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph draft: " + draft.draftId(), e);
        }
    }

    private Optional<GraphDraft> readDraft(String json, String draftId) {
        try {
            return Optional.of(objectMapper.readValue(json, GraphDraft.class));
        } catch (JsonProcessingException e) {
            log.warn("Skipping corrupt visual graph draft revision '{}': {}", draftId, e.getMessage());
            return Optional.empty();
        }
    }
}
