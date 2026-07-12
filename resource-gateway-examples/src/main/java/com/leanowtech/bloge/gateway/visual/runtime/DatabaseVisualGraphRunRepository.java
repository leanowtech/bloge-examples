package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;
import com.leanowtech.bloge.gateway.visual.persistence.TransactionCommitActions;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed repository for visual graph run history records.
 */
public class DatabaseVisualGraphRunRepository implements VisualGraphRunRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseVisualGraphRunRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_run_records (
                run_id VARCHAR(255) PRIMARY KEY,
                source_kind VARCHAR(64) NOT NULL,
                draft_id VARCHAR(255),
                draft_revision BIGINT NOT NULL,
                publication_id VARCHAR(255),
                created_at VARCHAR(64) NOT NULL,
                run_json CLOB NOT NULL
            )
            """;

    private static final String SELECT_ALL = "SELECT run_id, run_json FROM visual_graph_run_records";
    private static final String INSERT = """
            INSERT INTO visual_graph_run_records
                (run_id, source_kind, draft_id, draft_revision, publication_id, created_at, run_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final ConcurrentHashMap<String, VisualGraphRunRecord> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner evidenceSigner;
    private final VisualChangeEventPublisher changePublisher;

    /**
     * @param jdbc JDBC template for H2 access
     * @param objectMapper Jackson mapper for run serialization
     */
    public DatabaseVisualGraphRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, new DatabaseVisualEvidenceSigner(jdbc),
                VisualChangeEventPublisher.unavailable());
    }

    public DatabaseVisualGraphRunRepository(JdbcTemplate jdbc,
                                            ObjectMapper objectMapper,
                                            VisualEvidenceSigner evidenceSigner) {
        this(jdbc, objectMapper, evidenceSigner, VisualChangeEventPublisher.unavailable());
    }

    public DatabaseVisualGraphRunRepository(JdbcTemplate jdbc,
                                            ObjectMapper objectMapper,
                                            VisualEvidenceSigner evidenceSigner,
                                            VisualChangeEventPublisher changePublisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.evidenceSigner = evidenceSigner == null ? VisualEvidenceSigner.unavailable() : evidenceSigner;
        this.changePublisher = changePublisher == null
                ? VisualChangeEventPublisher.unavailable()
                : changePublisher;
    }

    /**
     * Initializes table and cache.
     */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        cache.clear();
        jdbc.query(SELECT_ALL, (rs, rowNum) -> {
            String runId = rs.getString("run_id");
            String json = rs.getString("run_json");
            try {
                VisualGraphRunRecord record = objectMapper.readValue(json, VisualGraphRunRecord.class);
                cache.put(runId, record);
                log.info("Loaded visual graph run record from DB: {}", runId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual graph run record '{}': {}", runId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualGraphRunRepository initialized with {} records", cache.size());
    }

    @Override
    public Collection<VisualGraphRunRecord> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualGraphRunRecord::createdAt).reversed()
                        .thenComparing(VisualGraphRunRecord::runId))
                .toList();
    }

    @Override
    public Optional<VisualGraphRunRecord> find(String runId) {
        return Optional.ofNullable(cache.get(runId));
    }

    @Override
    @Transactional
    public VisualGraphRunRecord create(VisualGraphRunRecord record) {
        String runId = record.runId().isBlank() ? UUID.randomUUID().toString() : record.runId();
        VisualGraphRunRecord identified = record.withIdentity(runId, Instant.now());
        VisualGraphRunRecord stored = identified.withEvidenceSeal(
                evidenceSigner.seal(identified.evidenceMaterialFingerprint()));
        persist(stored);
        changePublisher.publish(new VisualChangeFact("RUN_COMPLETED", stored.tenantId(), stored.namespace(),
                stored.environment(),
                new VisualChangeFact.Aggregate("RUN", stored.runId(), 1,
                        stored.evidenceMaterialFingerprint()),
                "/api/integration/runs/" + stored.runId() + "/evidence", stored.sourceKind()));
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.put(runId, stored);
            log.info("Created visual graph run record: {} source={} graph={} success={}",
                    stored.runId(), stored.sourceKind(), stored.graphName(), stored.success());
        });
        return stored;
    }

    @Override
    public VisualEvidenceSigner evidenceSigner() {
        return evidenceSigner;
    }

    private void persist(VisualGraphRunRecord record) {
        try {
            jdbc.update(INSERT,
                    record.runId(),
                    record.sourceKind(),
                    record.draftId(),
                    record.draftRevision(),
                    record.publicationId(),
                    record.createdAt().toString(),
                    objectMapper.writeValueAsString(record));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph run record: " + record.runId(), e);
        }
    }
}
