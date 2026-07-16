package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** JDBC repository for terminal, sanitized test-run records. */
public final class DatabaseTestRunRepository implements TestRunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseTestRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_run_records (
                    run_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    evidence_class VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_json CLOB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_run_scope_time
                ON rg_test_run_records (tenant_id, environment_id, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_run_operations_time
                ON rg_test_run_records (created_at, status)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_run_retention
                ON rg_test_run_records (expires_at)
                """);
    }

    @Override
    public TestRunRecord create(TestRunRecord record) {
        if (record == null || record.evidence() == null || record.runId() == null || record.runId().isBlank()) {
            throw new IllegalArgumentException("Complete test-run record is required");
        }
        if (record.evidence().evidenceClass() == TestRunEvidence.EvidenceClass.CERTIFIABLE
                && (record.integrity().signatureStatus()
                != TestEvidenceIntegrity.SignatureStatus.VERIFIED
                || !record.integrity().independentlyVerifiable())) {
            throw new IllegalArgumentException(
                    "Certifiable test-run evidence requires a verified full-evidence signature");
        }
        int rows = jdbc.update("""
                INSERT INTO rg_test_run_records (
                    run_id, tenant_id, environment_id, target_id, status, evidence_class,
                    created_at, expires_at, record_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.runId(), record.tenantId(), record.environmentId(), record.target().id(),
                record.evidence().status().name(), record.evidence().evidenceClass().name(),
                Timestamp.from(record.createdAt()), Timestamp.from(record.expiresAt()), write(record));
        if (rows != 1) {
            throw new IllegalStateException("Test-run insert did not create exactly one row");
        }
        return record;
    }

    @Override
    public Optional<TestRunRecord> find(String tenantId, String environmentId, String runId) {
        List<TestRunRecord> records = jdbc.query("""
                        SELECT record_json FROM rg_test_run_records
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                          AND expires_at > CURRENT_TIMESTAMP
                        """, (rs, row) -> read(rs.getString("record_json")),
                tenantId, environmentId, runId);
        return records.stream().findFirst();
    }

    private String write(TestRunRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize test-run record", failure);
        }
    }

    private TestRunRecord read(String value) {
        try {
            return objectMapper.readValue(value, TestRunRecord.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored test-run record is corrupt", failure);
        }
    }
}
