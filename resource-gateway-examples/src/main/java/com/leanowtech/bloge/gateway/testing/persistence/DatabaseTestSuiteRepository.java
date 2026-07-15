package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC suite registry using immutable tenant/environment/id/revision keys. */
public final class DatabaseTestSuiteRepository implements TestSuiteRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Creates a suite repository over the independent test-runtime database. */
    public DatabaseTestSuiteRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Creates the additive suite table without changing fixture or run tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suites (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    fingerprint VARCHAR(80) NOT NULL,
                    suite_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, suite_id, revision)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public StoredTestSuite create(StoredTestSuite suite) {
        require(suite);
        Optional<StoredTestSuite> existing = find(suite.tenantId(), suite.environmentId(),
                suite.suiteId(), suite.revision());
        if (existing.isPresent()) {
            return equivalentOrConflict(existing.get(), suite);
        }
        try {
            jdbc.update("""
                    INSERT INTO rg_test_suites (
                        tenant_id, environment_id, suite_id, revision, fingerprint,
                        suite_json, created_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, suite.tenantId(), suite.environmentId(), suite.suiteId(), suite.revision(),
                    suite.fingerprint(), write(suite.suite()), Timestamp.from(suite.createdAt()),
                    suite.createdBy());
            return suite;
        } catch (DataIntegrityViolationException race) {
            return find(suite.tenantId(), suite.environmentId(), suite.suiteId(), suite.revision())
                    .map(value -> equivalentOrConflict(value, suite))
                    .orElseThrow(() -> race);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<StoredTestSuite> find(String tenantId, String environmentId,
                                          String suiteId, long revision) {
        List<StoredTestSuite> results = jdbc.query("""
                        SELECT tenant_id, environment_id, suite_id, revision, fingerprint,
                               suite_json, created_at, created_by
                        FROM rg_test_suites
                        WHERE tenant_id = ? AND environment_id = ?
                          AND suite_id = ? AND revision = ?
                        """, (rs, row) -> new StoredTestSuite("",
                        rs.getString("tenant_id"), rs.getString("environment_id"),
                        rs.getString("suite_id"), rs.getLong("revision"),
                        rs.getString("fingerprint"), read(rs.getString("suite_json"), TestSuite.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("created_by")),
                tenantId, environmentId, suiteId, revision);
        return results.stream().findFirst();
    }

    private static StoredTestSuite equivalentOrConflict(StoredTestSuite existing,
                                                         StoredTestSuite requested) {
        if (!existing.fingerprint().equals(requested.fingerprint())) {
            throw new TestSuiteConflictException(
                    "Test-suite revision already exists with different immutable content");
        }
        return existing;
    }

    private static void require(StoredTestSuite suite) {
        if (suite == null || suite.suiteId() == null || suite.suiteId().isBlank()
                || suite.revision() <= 0 || suite.fingerprint() == null || suite.fingerprint().isBlank()
                || suite.suite() == null || suite.createdAt() == null || suite.createdBy() == null
                || suite.createdBy().isBlank()) {
            throw new IllegalArgumentException("Complete immutable test-suite revision is required");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize test suite", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored test suite is corrupt", failure);
        }
    }
}
