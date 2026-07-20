package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestRunIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecordIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC repository for terminal, sanitized test-run records. */
public final class DatabaseTestRunRepository implements TestRunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestEvidenceIntegrityService evidenceIntegrity;

    /** Creates the repository with the same verification authority used to seal child evidence. */
    public DatabaseTestRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                     TestEvidenceIntegrityService evidenceIntegrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.evidenceIntegrity = Objects.requireNonNull(evidenceIntegrity, "evidenceIntegrity");
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
        TestRunRecord snapshot = TestRunRecordIntegrity.verifiedCreateSnapshot(
                objectMapper, evidenceIntegrity, record);
        int rows = jdbc.update("""
                INSERT INTO rg_test_run_records (
                    run_id, tenant_id, environment_id, target_id, status, evidence_class,
                    created_at, expires_at, record_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.runId(), snapshot.tenantId(), snapshot.environmentId(), snapshot.target().id(),
                snapshot.evidence().status().name(), snapshot.evidence().evidenceClass().name(),
                Timestamp.from(snapshot.createdAt()), Timestamp.from(snapshot.expiresAt()), write(snapshot));
        if (rows != 1) {
            throw new IllegalStateException("Test-run insert did not create exactly one row");
        }
        return snapshot;
    }

    @Override
    public Optional<TestRunRecord> find(String tenantId, String environmentId, String runId) {
        List<StoredRow> records = jdbc.query("""
                        SELECT run_id, tenant_id, environment_id, target_id, status, evidence_class,
                               created_at, expires_at, record_json
                        FROM rg_test_run_records
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                          AND expires_at > CURRENT_TIMESTAMP
                        """, (rs, row) -> new StoredRow(read(rs.getString("record_json")),
                        rs.getString("run_id"), rs.getString("tenant_id"),
                        rs.getString("environment_id"), rs.getString("target_id"),
                        rs.getString("status"), rs.getString("evidence_class"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()),
                tenantId, environmentId, runId);
        return records.stream().findFirst().map(stored -> verifyStoredRow(
                stored, tenantId, environmentId, runId));
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
            throw new TestRunIntegrityException(failure);
        }
    }

    private TestRunRecord verifyStoredRow(StoredRow stored, String tenantId,
                                          String environmentId, String runId) {
        TestRunRecord snapshot = TestRunRecordIntegrity.verifiedSnapshot(
                objectMapper, evidenceIntegrity, stored.record(), tenantId, environmentId, runId);
        if (!Objects.equals(stored.runId(), snapshot.runId())
                || !Objects.equals(stored.tenantId(), snapshot.tenantId())
                || !Objects.equals(stored.environmentId(), snapshot.environmentId())
                || !Objects.equals(stored.targetId(), snapshot.target().id())
                || !Objects.equals(stored.status(), snapshot.evidence().status().name())
                || !Objects.equals(stored.evidenceClass(), snapshot.evidence().evidenceClass().name())
                || !Objects.equals(stored.createdAt(), snapshot.createdAt())
                || !Objects.equals(stored.expiresAt(), snapshot.expiresAt())) {
            throw new TestRunIntegrityException();
        }
        return snapshot;
    }

    private record StoredRow(TestRunRecord record, String runId, String tenantId,
                             String environmentId, String targetId, String status,
                             String evidenceClass, Instant createdAt, Instant expiresAt) {
    }
}
