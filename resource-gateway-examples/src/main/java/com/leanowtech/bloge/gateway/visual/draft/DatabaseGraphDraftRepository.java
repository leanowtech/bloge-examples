package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Comparator;
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

    private static final String SELECT_ALL = "SELECT draft_id, draft_json FROM visual_graph_drafts";
    private static final String UPSERT = "MERGE INTO visual_graph_drafts (draft_id, revision, draft_json) KEY (draft_id) VALUES (?, ?, ?)";
    private static final String DELETE = "DELETE FROM visual_graph_drafts WHERE draft_id = ?";

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
    public GraphDraft save(GraphDraft draft) {
        String draftId = draft.draftId().isBlank() ? UUID.randomUUID().toString() : draft.draftId();
        long currentRevision = cache.getOrDefault(draftId, draft.withIdentity(draftId, 0)).revision();
        long nextRevision = Math.max(draft.revision(), currentRevision) + 1;
        GraphDraft stored = draft.withIdentity(draftId, nextRevision);
        persist(stored);
        cache.put(draftId, stored);
        log.info("Saved visual graph draft: {}@{}", draftId, nextRevision);
        return stored;
    }

    @Override
    public void delete(String draftId) {
        jdbc.update(DELETE, draftId);
        cache.remove(draftId);
        log.info("Deleted visual graph draft: {}", draftId);
    }

    private void persist(GraphDraft draft) {
        try {
            jdbc.update(UPSERT, draft.draftId(), draft.revision(), objectMapper.writeValueAsString(draft));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph draft: " + draft.draftId(), e);
        }
    }
}
