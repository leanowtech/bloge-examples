package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed repository for visual graph golden regression cases.
 */
public class DatabaseVisualGraphGoldenCaseRepository implements VisualGraphGoldenCaseRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseVisualGraphGoldenCaseRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_golden_cases (
                case_id VARCHAR(255) PRIMARY KEY,
                publication_id VARCHAR(255) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                case_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = "SELECT case_id, case_json FROM visual_graph_golden_cases";
    private static final String DELETE = "DELETE FROM visual_graph_golden_cases WHERE case_id = ?";
    private static final String INSERT = """
            INSERT INTO visual_graph_golden_cases
                (case_id, publication_id, created_at, case_json)
            VALUES (?, ?, ?, ?)
            """;

    private final ConcurrentHashMap<String, VisualGraphGoldenCase> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualGraphGoldenCaseRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
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
            String caseId = rs.getString("case_id");
            String json = rs.getString("case_json");
            try {
                VisualGraphGoldenCase testCase = objectMapper.readValue(json, VisualGraphGoldenCase.class);
                cache.put(caseId, testCase);
                log.info("Loaded visual graph golden case from DB: {}", caseId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual graph golden case '{}': {}", caseId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualGraphGoldenCaseRepository initialized with {} cases", cache.size());
    }

    @Override
    public Collection<VisualGraphGoldenCase> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualGraphGoldenCase::createdAt).reversed()
                        .thenComparing(VisualGraphGoldenCase::caseId))
                .toList();
    }

    @Override
    public Optional<VisualGraphGoldenCase> find(String caseId) {
        return Optional.ofNullable(cache.get(caseId));
    }

    @Override
    public VisualGraphGoldenCase save(VisualGraphGoldenCase testCase) {
        String caseId = testCase.caseId().isBlank() ? UUID.randomUUID().toString() : testCase.caseId();
        VisualGraphGoldenCase stored = testCase.withIdentity(caseId, testCase.createdAt() == null
                ? Instant.now()
                : testCase.createdAt());
        persist(stored);
        cache.put(caseId, stored);
        log.info("Saved visual graph golden case: {} publication={}", stored.caseId(), stored.publicationId());
        return stored;
    }

    private void persist(VisualGraphGoldenCase testCase) {
        try {
            jdbc.update(DELETE, testCase.caseId());
            jdbc.update(INSERT,
                    testCase.caseId(),
                    testCase.publicationId(),
                    testCase.createdAt().toString(),
                    objectMapper.writeValueAsString(testCase));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph golden case: "
                    + testCase.caseId(), e);
        }
    }
}
