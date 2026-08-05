package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.persistence.TransactionCommitActions;
import com.leanowtech.bloge.gateway.visual.validation.VisualSecretGuard;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String SELECT_BY_ID = "SELECT draft_json FROM visual_graph_drafts WHERE draft_id = ?";
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
    private static final String SELECT_HISTORY_REVISIONS = """
            SELECT draft_id, draft_json
            FROM visual_graph_draft_revisions
            ORDER BY draft_id ASC, revision DESC
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

    private final ConcurrentHashMap<String, GraphDraft> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualChangeEventPublisher changePublisher;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseGraphDraftRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, VisualChangeEventPublisher.unavailable());
    }

    public DatabaseGraphDraftRepository(JdbcTemplate jdbc,
                                        ObjectMapper objectMapper,
                                        VisualChangeEventPublisher changePublisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.changePublisher = changePublisher == null
                ? VisualChangeEventPublisher.unavailable()
                : changePublisher;
    }

    /** Initializes tables and cache, including for lightweight explicit repository wiring. */
    @PostConstruct
    public void init() {
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
        // The committed cache cannot satisfy read-your-writes while an authoring transaction is open.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return jdbc.query(SELECT_BY_ID,
                            (rs, rowNum) -> readDraft(rs.getString("draft_json"), draftId), draftId)
                    .stream()
                    .flatMap(Optional::stream)
                    .findFirst();
        }
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
    public List<GraphDraftHistorySummary> history() {
        Map<String, List<GraphDraft>> revisionsByDraftId = new LinkedHashMap<>();
        jdbc.query(SELECT_HISTORY_REVISIONS, rs -> {
            String draftId = rs.getString("draft_id");
            readDraft(rs.getString("draft_json"), draftId)
                    .ifPresent(draft -> revisionsByDraftId.computeIfAbsent(draftId, ignored -> new ArrayList<>())
                            .add(draft));
        });
        cache.keySet().stream()
                .sorted()
                .forEach(draftId -> revisionsByDraftId.computeIfAbsent(draftId, ignored -> new ArrayList<>()));
        return revisionsByDraftId.entrySet().stream()
                .map(entry -> GraphDraftHistorySummary.from(entry.getKey(), cache.get(entry.getKey()),
                        entry.getValue()))
                .sorted(Comparator.comparingLong(GraphDraftHistorySummary::latestRevision).reversed()
                        .thenComparing(GraphDraftHistorySummary::draftId))
                .toList();
    }

    @Override
    @Transactional
    public GraphDraft save(GraphDraft draft) {
        VisualSecretGuard.requireNoDraftSecrets(draft);
        String draftId = draft.draftId().isBlank() ? UUID.randomUUID().toString() : draft.draftId();
        GraphDraft current = cache.get(draftId);
        GraphDraft previous = current == null ? latestRevision(draftId).orElse(null) : current;
        long previousRevision = previous == null ? 0 : previous.revision();
        long nextRevision = Math.max(draft.revision(), previousRevision) + 1;
        GraphDraft stored = draft.withIdentity(draftId, nextRevision)
                .withRevisionMetadata(draft.revisionMetadata().storedFrom(
                        previous == null ? null : previous.revisionMetadata(), "Saved draft."));
        persist(stored);
        appendEvent(previous == null ? "GRAPH_DRAFT_CREATED" : "GRAPH_DRAFT_UPDATED", stored);
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.put(draftId, stored);
            log.info("Saved visual graph draft: {}@{}", draftId, nextRevision);
        });
        return stored;
    }

    @Override
    @Transactional
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
        appendEvent("GRAPH_DRAFT_UPDATED", stored);
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.put(draftId, stored);
            log.info("Patched visual graph draft: {}@{}", draftId, stored.revision());
        });
        return Optional.of(stored);
    }

    @Override
    @Transactional
    public synchronized void delete(String draftId, GraphDraft.RevisionMetadata metadata) {
        GraphDraft current = Optional.ofNullable(cache.get(draftId)).orElseGet(
                () -> latestRevision(draftId).orElse(null));
        if (current != null) {
            GraphDraft deleted = current.withIdentity(draftId, current.revision() + 1)
                    .withRevisionMetadata((metadata == null ? GraphDraft.RevisionMetadata.empty() : metadata)
                            .storedFrom(current.revisionMetadata(), "Deleted draft."));
            persistRevision(deleted);
            jdbc.update(DELETE, draftId);
            appendEvent("GRAPH_DRAFT_DELETED", deleted);
        } else {
            jdbc.update(DELETE, draftId);
        }
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.remove(draftId);
            log.info("Deleted visual graph draft: {}", draftId);
        });
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

    private void persistRevision(GraphDraft draft) {
        try {
            jdbc.update(UPSERT_REVISION, draft.draftId(), draft.revision(), objectMapper.writeValueAsString(draft));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph draft revision: " + draft.draftId(), e);
        }
    }

    private void appendEvent(String eventType, GraphDraft draft) {
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "draft", draft.withNodeFixtures(Map.of())));
        changePublisher.publish(new VisualChangeFact(eventType, draft.tenantId(), draft.namespace(),
                draft.environment(),
                new VisualChangeFact.Aggregate("GRAPH_DRAFT", draft.draftId(), draft.revision(),
                        fingerprint),
                "/api/integration/drafts/" + draft.draftId() + "/export?revision=" + draft.revision(),
                draft.revisionMetadata().changeSource()));
    }

    private Optional<GraphDraft> latestRevision(String draftId) {
        return revisions(draftId).stream().findFirst();
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
