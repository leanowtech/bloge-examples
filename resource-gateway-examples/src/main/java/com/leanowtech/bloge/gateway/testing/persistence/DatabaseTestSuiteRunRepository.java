package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for recoverable suite-run checkpoints and terminal aggregate evidence.
 *
 * <p>Scope columns and the idempotency key are indexed outside JSON so authorization and duplicate
 * suppression happen before deserialization. The unique tenant/environment/client key is the final
 * race barrier when multiple runtime replicas receive the same request concurrently.</p>
 */
public final class DatabaseTestSuiteRunRepository implements TestSuiteRunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Creates a repository over the isolated test-runtime JDBC store. */
    public DatabaseTestSuiteRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Creates the suite-run table and scoped lookup indexes when absent. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_run_records (
                    suite_run_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    suite_revision BIGINT NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    evidence_fingerprint VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_run_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_scope_time
                ON rg_test_suite_run_records (tenant_id, environment_id, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_suite
                ON rg_test_suite_run_records (
                    tenant_id, environment_id, suite_id, suite_revision, created_at
                )
                """);
    }

    @Override
    public TestSuiteRunRecord create(TestSuiteRunRecord record) {
        requireComplete(record);
        try {
            int rows = jdbc.update("""
                    INSERT INTO rg_test_suite_run_records (
                        suite_run_id, tenant_id, environment_id, client_request_id,
                        suite_id, suite_revision, status, evidence_fingerprint,
                        created_at, expires_at, record_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, record.suiteRunId(), record.tenantId(), record.environmentId(),
                    record.clientRequestId(), record.evidence().suiteRef().suiteId(),
                    record.evidence().suiteRef().revision(), record.evidence().status().name(),
                    record.evidenceFingerprint(), Timestamp.from(record.createdAt()),
                    Timestamp.from(record.expiresAt()), write(record));
            if (rows != 1) {
                throw new IllegalStateException("Suite-run insert did not create exactly one row");
            }
            return record;
        } catch (DuplicateKeyException duplicate) {
            throw new TestSuiteRunConflictException(
                    "Suite-run id or scoped clientRequestId already exists");
        }
    }

    @Override
    public TestSuiteRunRecord update(TestSuiteRunRecord record) {
        requireComplete(record);
        int rows = jdbc.update("""
                UPDATE rg_test_suite_run_records
                SET status = ?, evidence_fingerprint = ?, record_json = ?
                WHERE suite_run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND client_request_id = ? AND expires_at > CURRENT_TIMESTAMP
                """, record.evidence().status().name(), record.evidenceFingerprint(), write(record),
                record.suiteRunId(), record.tenantId(), record.environmentId(), record.clientRequestId());
        if (rows != 1) {
            throw new IllegalStateException("Suite-run checkpoint no longer exists in the authorized scope");
        }
        return record;
    }

    @Override
    public Optional<TestSuiteRunRecord> find(String tenantId, String environmentId, String suiteRunId) {
        return query("""
                        SELECT record_json FROM rg_test_suite_run_records
                        WHERE tenant_id = ? AND environment_id = ? AND suite_run_id = ?
                          AND expires_at > CURRENT_TIMESTAMP
                        """, tenantId, environmentId, suiteRunId);
    }

    @Override
    public Optional<TestSuiteRunRecord> findByClientRequestId(
            String tenantId, String environmentId, String clientRequestId) {
        return query("""
                        SELECT record_json FROM rg_test_suite_run_records
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                          AND expires_at > CURRENT_TIMESTAMP
                        """, tenantId, environmentId, clientRequestId);
    }

    private Optional<TestSuiteRunRecord> query(String sql, Object... arguments) {
        List<TestSuiteRunRecord> records = jdbc.query(sql,
                (rs, row) -> read(rs.getString("record_json")), arguments);
        return records.stream().findFirst();
    }

    private static void requireComplete(TestSuiteRunRecord record) {
        if (record == null || record.evidence() == null || record.suiteRunId() == null
                || record.suiteRunId().isBlank() || record.clientRequestId() == null
                || record.clientRequestId().isBlank() || record.createdAt() == null
                || record.expiresAt() == null) {
            throw new IllegalArgumentException("Complete suite-run checkpoint is required");
        }
    }

    private String write(TestSuiteRunRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize suite-run checkpoint", failure);
        }
    }

    private TestSuiteRunRecord read(String value) {
        try {
            return objectMapper.readValue(value, TestSuiteRunRecord.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored suite-run checkpoint is corrupt", failure);
        }
    }
}
