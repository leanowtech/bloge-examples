package com.leanowtech.bloge.gateway.visual.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.persistence.TransactionCommitActions;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Database-backed operator contract-suite authority with transactional change events. */
public class DatabaseVisualOperatorContractTestSuiteRepository
        implements VisualOperatorContractTestSuiteRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_operator_contract_test_suites (
                suite_id VARCHAR(255) PRIMARY KEY,
                revision BIGINT NOT NULL,
                suite_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = """
            SELECT suite_id, revision, suite_json FROM visual_operator_contract_test_suites
            """;
    private static final String CREATE_REVISION_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_operator_contract_test_suite_revisions (
                suite_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                suite_json CLOB NOT NULL,
                PRIMARY KEY (suite_id, revision)
            )
            """;
    private static final String SELECT_REVISION = """
            SELECT suite_json FROM visual_operator_contract_test_suite_revisions
            WHERE suite_id = ? AND revision = ?
            """;
    private static final String UPSERT = """
            MERGE INTO visual_operator_contract_test_suites (suite_id, revision, suite_json)
            KEY (suite_id) VALUES (?, ?, ?)
            """;
    private static final String INSERT_REVISION = """
            INSERT INTO visual_operator_contract_test_suite_revisions (suite_id, revision, suite_json)
            VALUES (?, ?, ?)
            """;

    private final Map<String, VisualOperatorContractTestSuite> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> revisions = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualChangeEventPublisher changePublisher;

    public DatabaseVisualOperatorContractTestSuiteRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, VisualChangeEventPublisher.unavailable());
    }

    public DatabaseVisualOperatorContractTestSuiteRepository(JdbcTemplate jdbc,
                                                             ObjectMapper objectMapper,
                                                             VisualChangeEventPublisher changePublisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.changePublisher = changePublisher == null
                ? VisualChangeEventPublisher.unavailable()
                : changePublisher;
    }

    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.execute(CREATE_REVISION_TABLE);
        cache.clear();
        revisions.clear();
        jdbc.query(SELECT_ALL, rs -> {
            String suiteId = rs.getString("suite_id");
            try {
                cache.put(suiteId, objectMapper.readValue(rs.getString("suite_json"),
                        VisualOperatorContractTestSuite.class));
                revisions.put(suiteId, rs.getLong("revision"));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Failed to load operator contract-test suite: " + suiteId,
                        exception);
            }
        });
    }

    @Override
    public Collection<VisualOperatorContractTestSuite> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualOperatorContractTestSuite::suiteId))
                .toList();
    }

    @Override
    public Optional<VisualOperatorContractTestSuite> find(String suiteId) {
        return Optional.ofNullable(cache.get(suiteId));
    }

    @Override
    public Optional<VisualOperatorContractTestSuite> findRevision(String suiteId, long revision) {
        return jdbc.query(SELECT_REVISION, (rs, rowNum) -> readSuite(rs.getString("suite_json"), suiteId),
                        suiteId, revision).stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public long revision(String suiteId) {
        return revisions.getOrDefault(suiteId, 0L);
    }

    @Override
    @Transactional
    public synchronized VisualOperatorContractTestSuite save(VisualOperatorContractTestSuite suite) {
        if (suite == null || suite.suiteId().isBlank()) {
            throw new IllegalArgumentException("suiteId is required.");
        }
        long revision = revisions.getOrDefault(suite.suiteId(), 0L) + 1;
        String json;
        try {
            json = objectMapper.writeValueAsString(suite);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize operator contract-test suite: " + suite.suiteId(),
                    exception);
        }
        jdbc.update(UPSERT, suite.suiteId(), revision, json);
        jdbc.update(INSERT_REVISION, suite.suiteId(), revision, json);
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of("contractTestSuite", suite));
        String eventType = revision == 1 ? "CONTRACT_TEST_SUITE_CREATED" : "CONTRACT_TEST_SUITE_UPDATED";
        changePublisher.publish(new VisualChangeFact(eventType, VisualChangeFact.GLOBAL_SCOPE, "shared",
                VisualChangeFact.GLOBAL_SCOPE,
                new VisualChangeFact.Aggregate("CONTRACT_TEST_SUITE", suite.suiteId(), revision,
                        fingerprint),
                "/api/integration/operator-test-suites/" + suite.suiteId() + "?revision=" + revision, ""));
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.put(suite.suiteId(), suite);
            revisions.put(suite.suiteId(), revision);
        });
        return suite;
    }

    private Optional<VisualOperatorContractTestSuite> readSuite(String json, String suiteId) {
        try {
            return Optional.of(objectMapper.readValue(json, VisualOperatorContractTestSuite.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to load operator contract-test suite revision: " + suiteId,
                    exception);
        }
    }
}
