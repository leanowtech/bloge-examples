package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
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
    private final TestSuiteProtocolCodec codec;

    /** Creates a suite repository over the independent test-runtime database. */
    public DatabaseTestSuiteRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.codec = new TestSuiteProtocolCodec(this.objectMapper);
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
        StoredTestSuite requested = StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, suite);
        Optional<StoredTestSuite> existing = find(requested.tenantId(), requested.environmentId(),
                requested.suiteId(), requested.revision());
        if (existing.isPresent()) {
            return equivalentOrConflict(existing.get(), requested);
        }
        try {
            jdbc.update("""
                    INSERT INTO rg_test_suites (
                        tenant_id, environment_id, suite_id, revision, fingerprint,
                        suite_json, created_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, requested.tenantId(), requested.environmentId(), requested.suiteId(),
                    requested.revision(), requested.fingerprint(), codec.write(requested.suite()),
                    Timestamp.from(requested.createdAt()), requested.createdBy());
            return requested;
        } catch (DataIntegrityViolationException race) {
            return find(requested.tenantId(), requested.environmentId(), requested.suiteId(),
                    requested.revision())
                    .map(value -> equivalentOrConflict(value, requested))
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
                        """, (rs, row) -> {
                    try {
                        return new StoredTestSuite("", rs.getString("tenant_id"),
                                rs.getString("environment_id"), rs.getString("suite_id"),
                                rs.getLong("revision"), rs.getString("fingerprint"),
                                codec.read(rs.getString("suite_json")),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getString("created_by"));
                    } catch (RuntimeException corrupt) {
                        throw new TestSuiteIntegrityException(corrupt);
                    }
                },
                tenantId, environmentId, suiteId, revision);
        return results.stream().findFirst().map(value ->
                StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, value,
                        tenantId, environmentId, suiteId, revision));
    }

    private StoredTestSuite equivalentOrConflict(StoredTestSuite existing,
                                                 StoredTestSuite requested) {
        if (!existing.fingerprint().equals(requested.fingerprint())) {
            throw new TestSuiteConflictException(
                    "Test-suite revision already exists with different immutable content");
        }
        return StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, existing, requested);
    }

}
