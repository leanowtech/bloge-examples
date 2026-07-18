package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JDBC store for immutable signed suite-stability analyses.
 *
 * <p>Scope and idempotency columns are selected before JSON deserialization. The table contains no
 * child payload, fixture value, context, or output; source evidence remains in its governed stores.</p>
 */
public final class DatabaseTestSuiteStabilityRunRepository
        implements TestSuiteStabilityRunRepository {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc isolated test-runtime JDBC adapter
     * @param objectMapper canonical protocol mapper
     */
    public DatabaseTestSuiteStabilityRunRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Creates the terminal stability table and scoped lookup indexes when absent. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_records (
                    stability_run_id VARCHAR(255) PRIMARY KEY,
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
                    CONSTRAINT uq_rg_test_suite_stability_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_scope_time
                ON rg_test_suite_stability_records (tenant_id, environment_id, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_suite
                ON rg_test_suite_stability_records (
                    tenant_id, environment_id, suite_id, suite_revision, created_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_retention
                ON rg_test_suite_stability_records (expires_at)
                """);
    }

    @Override
    public TestSuiteStabilityRunRecord create(TestSuiteStabilityRunRecord record) {
        requireComplete(record);
        try {
            int rows = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_records (
                        stability_run_id, tenant_id, environment_id, client_request_id,
                        suite_id, suite_revision, status, evidence_fingerprint,
                        created_at, expires_at, record_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, record.stabilityRunId(), record.tenantId(), record.environmentId(),
                    record.clientRequestId(), record.evidence().suiteRef().suiteId(),
                    record.evidence().suiteRef().revision(), record.evidence().status().name(),
                    record.evidenceFingerprint(), Timestamp.from(record.createdAt()),
                    Timestamp.from(record.expiresAt()), write(record));
            if (rows != 1) {
                throw new IllegalStateException(
                        "Stability analysis insert did not create exactly one row");
            }
            return record;
        } catch (DuplicateKeyException duplicate) {
            throw new TestSuiteStabilityRunConflictException(
                    "Stability analysis id or scoped clientRequestId already exists");
        }
    }

    @Override
    public Optional<TestSuiteStabilityRunRecord> find(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        return query("""
                SELECT record_json FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ? AND stability_run_id = ?
                  AND expires_at > CURRENT_TIMESTAMP
                """, tenantId, environmentId, stabilityRunId);
    }

    @Override
    public Optional<TestSuiteStabilityRunRecord> findByClientRequestId(
            String tenantId,
            String environmentId,
            String clientRequestId) {
        return query("""
                SELECT record_json FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                  AND expires_at > CURRENT_TIMESTAMP
                """, tenantId, environmentId, clientRequestId);
    }

    private Optional<TestSuiteStabilityRunRecord> query(String sql, Object... arguments) {
        List<TestSuiteStabilityRunRecord> records = jdbc.query(sql,
                (rs, row) -> read(rs.getString("record_json")), arguments);
        return records.stream().findFirst();
    }

    private void requireComplete(TestSuiteStabilityRunRecord record) {
        if (record == null || record.evidence() == null || record.attestation() == null
                || record.createdAt() == null || record.expiresAt() == null
                || !record.expiresAt().isAfter(record.createdAt())
                || !record.stabilityRunId().equals(record.evidence().stabilityRunId())
                || !record.stabilityRunId().equals(record.attestation().stabilityRunId())
                || !record.clientRequestId().equals(record.evidence().clientRequestId())
                || !record.evidenceFingerprint().equals(record.attestation().evidenceFingerprint())
                || !FINGERPRINT.matcher(record.requestFingerprint()).matches()
                || !FINGERPRINT.matcher(record.evidenceFingerprint()).matches()
                || !record.attestation().terminallyVerifiable()
                || !record.requestFingerprint().equals(record.attestation().requestFingerprint())
                || !sourceRefs(record).equals(
                record.attestation().sourceSuiteEvidenceRefs())
                || !record.evidenceFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, record.evidence()))) {
            throw new IllegalArgumentException(
                    "Complete signed terminal stability record is required");
        }
    }

    private static List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sourceRefs(
            TestSuiteStabilityRunRecord record) {
        return record.evidence().attempts().stream()
                .filter(value -> !value.suiteRunId().isBlank()
                        && !value.aggregateEvidenceFingerprint().isBlank())
                .map(value -> new TestSuiteStabilityAttestation.SourceSuiteEvidenceRef(
                        value.attempt(), value.suiteRunId(),
                        value.aggregateEvidenceFingerprint()))
                .toList();
    }

    private String write(TestSuiteStabilityRunRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize stability analysis", failure);
        }
    }

    private TestSuiteStabilityRunRecord read(String value) {
        try {
            return objectMapper.readValue(value, TestSuiteStabilityRunRecord.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored stability analysis is corrupt", failure);
        }
    }
}
