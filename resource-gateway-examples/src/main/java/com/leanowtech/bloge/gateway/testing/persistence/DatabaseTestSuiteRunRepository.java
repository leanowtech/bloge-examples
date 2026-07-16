package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.AbandonedTestSuiteRun;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
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
                    checkpoint_version BIGINT NOT NULL DEFAULT 0,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_checkpoint_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_run_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_run_records
                ADD COLUMN IF NOT EXISTS checkpoint_version BIGINT NOT NULL DEFAULT 0
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_run_records
                ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(255) NOT NULL DEFAULT 'legacy-owner'
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_run_records
                ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP WITH TIME ZONE
                    NOT NULL DEFAULT CURRENT_TIMESTAMP
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_run_records
                ADD COLUMN IF NOT EXISTS last_checkpoint_at TIMESTAMP WITH TIME ZONE
                    NOT NULL DEFAULT CURRENT_TIMESTAMP
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_scope_time
                ON rg_test_suite_run_records (tenant_id, environment_id, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_abandoned
                ON rg_test_suite_run_records (status, lease_expires_at, checkpoint_version)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_suite
                ON rg_test_suite_run_records (
                    tenant_id, environment_id, suite_id, suite_revision, created_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_operations
                ON rg_test_suite_run_records (
                    status, last_checkpoint_at, lease_expires_at, expires_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_run_retention
                ON rg_test_suite_run_records (expires_at)
                """);
    }

    @Override
    public TestSuiteRunRecord create(TestSuiteRunRecord record, TestSuiteRunLease lease) {
        requireComplete(record);
        requireLeaseValue(lease);
        try {
            int rows = jdbc.update("""
                    INSERT INTO rg_test_suite_run_records (
                        suite_run_id, tenant_id, environment_id, client_request_id,
                        suite_id, suite_revision, status, evidence_fingerprint,
                        checkpoint_version, lease_owner, lease_expires_at, last_checkpoint_at,
                        created_at, expires_at, record_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                    """, record.suiteRunId(), record.tenantId(), record.environmentId(),
                    record.clientRequestId(), record.evidence().suiteRef().suiteId(),
                    record.evidence().suiteRef().revision(), record.evidence().status().name(),
                    record.evidenceFingerprint(), lease.ownerId(), Timestamp.from(lease.expiresAt()),
                    Timestamp.from(record.createdAt()), Timestamp.from(record.createdAt()),
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

    /** Uses database time as the lease authority so replica clock skew cannot expire a live owner. */
    @Override
    public Instant currentTime() {
        Timestamp current = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (current == null) {
            throw new IllegalStateException("Test-runtime database did not return its current time");
        }
        return current.toInstant();
    }

    @Override
    public TestSuiteRunRecord update(TestSuiteRunRecord record, TestSuiteRunLease lease,
                                     Instant observedAt) {
        requireComplete(record);
        requireLease(lease, observedAt);
        int rows = jdbc.update("""
                UPDATE rg_test_suite_run_records
                SET status = ?, evidence_fingerprint = ?, record_json = ?,
                    checkpoint_version = checkpoint_version + 1,
                    lease_expires_at = ?, last_checkpoint_at = ?
                WHERE suite_run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND client_request_id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND lease_expires_at > ? AND expires_at > ?
                """, record.evidence().status().name(), record.evidenceFingerprint(), write(record),
                Timestamp.from(lease.expiresAt()), Timestamp.from(observedAt),
                record.suiteRunId(), record.tenantId(), record.environmentId(), record.clientRequestId(),
                lease.ownerId(), Timestamp.from(observedAt), Timestamp.from(observedAt));

        if (rows != 1) {
            throw new IllegalStateException("Suite-run lease was lost or checkpoint is already terminal");
        }
        return record;
    }

    @Override
    public boolean renewLease(String tenantId, String environmentId, String suiteRunId,
                              String ownerId, Instant expiresAt, Instant observedAt) {
        TestSuiteRunLease lease = new TestSuiteRunLease(ownerId, expiresAt);
        requireLease(lease, observedAt);
        return jdbc.update("""
                UPDATE rg_test_suite_run_records
                SET checkpoint_version = checkpoint_version + 1,
                    lease_expires_at = ?, last_checkpoint_at = ?
                WHERE tenant_id = ? AND environment_id = ? AND suite_run_id = ?
                  AND status = 'RUNNING' AND lease_owner = ?
                  AND lease_expires_at > ? AND expires_at > ?
                """, Timestamp.from(expiresAt), Timestamp.from(observedAt), tenantId, environmentId,
                suiteRunId, lease.ownerId(), Timestamp.from(observedAt), Timestamp.from(observedAt)) == 1;
    }

    @Override
    public List<AbandonedTestSuiteRun> findAbandoned(Instant observedAt, int limit) {
        if (observedAt == null) {
            throw new IllegalArgumentException("Abandoned-run observation time is required");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        return jdbc.query("""
                SELECT record_json, checkpoint_version, lease_owner, lease_expires_at
                FROM rg_test_suite_run_records
                WHERE status = 'RUNNING' AND lease_expires_at <= ? AND expires_at > ?
                ORDER BY lease_expires_at, suite_run_id
                LIMIT ?
                """, (rs, row) -> new AbandonedTestSuiteRun(read(rs.getString("record_json")),
                        rs.getLong("checkpoint_version"), rs.getString("lease_owner"),
                        rs.getTimestamp("lease_expires_at").toInstant()),
                Timestamp.from(observedAt), Timestamp.from(observedAt), boundedLimit);
    }

    @Override
    public boolean reconcileAbandoned(AbandonedTestSuiteRun abandoned, TestSuiteRunRecord terminal,
                                      Instant observedAt) {
        requireComplete(terminal);
        if (abandoned == null || observedAt == null
                || !abandoned.record().suiteRunId().equals(terminal.suiteRunId())
                || terminal.evidence().status() == com.leanowtech.bloge.gateway.testing.domain
                .TestSuiteRunEvidence.Status.RUNNING) {
            throw new IllegalArgumentException("Matching terminal abandoned-run evidence is required");
        }
        return jdbc.update("""
                UPDATE rg_test_suite_run_records
                SET status = ?, evidence_fingerprint = ?, record_json = ?,
                    checkpoint_version = checkpoint_version + 1, last_checkpoint_at = ?
                WHERE suite_run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND status = 'RUNNING' AND checkpoint_version = ?
                  AND lease_owner = ? AND lease_expires_at <= ? AND expires_at > ?
                """, terminal.evidence().status().name(), terminal.evidenceFingerprint(), write(terminal),
                Timestamp.from(observedAt), terminal.suiteRunId(), terminal.tenantId(),
                terminal.environmentId(), abandoned.checkpointVersion(), abandoned.leaseOwner(),
                Timestamp.from(observedAt), Timestamp.from(observedAt)) == 1;
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

    /** {@inheritDoc} */
    @Override
    public List<TestSuiteRunRecord> findTerminalBySuite(
            String tenantId, String environmentId, String suiteId, long revision, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1001));
        return jdbc.query("""
                        SELECT record_json FROM rg_test_suite_run_records
                        WHERE tenant_id = ? AND environment_id = ?
                          AND suite_id = ? AND suite_revision = ?
                          AND status <> 'RUNNING' AND expires_at > CURRENT_TIMESTAMP
                        ORDER BY created_at DESC, suite_run_id
                        LIMIT ?
                        """, (rs, row) -> read(rs.getString("record_json")),
                tenantId, environmentId, suiteId, revision, boundedLimit);
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
        TestSuiteRunAttestation attestation = record.attestation();
        boolean running = record.evidence().status() == TestSuiteRunEvidence.Status.RUNNING;
        boolean verified = attestation.signatureStatus()
                == TestSuiteRunAttestation.SignatureStatus.VERIFIED
                && attestation.independentlyVerifiable();
        boolean unavailableTerminal = !running
                && attestation.signatureStatus()
                == TestSuiteRunAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE
                && record.evidence().status() == TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE
                && record.evidence().promotion().status()
                == TestSuiteRunEvidence.PromotionStatus.BLOCKED;
        boolean scopeMatches = attestation.scope() == (running
                ? TestSuiteRunAttestation.Scope.CHECKPOINT
                : TestSuiteRunAttestation.Scope.TERMINAL);
        boolean identityMatches = record.suiteRunId().equals(attestation.suiteRunId())
                && record.requestFingerprint().equals(attestation.requestFingerprint());
        boolean fingerprintMatches = running
                ? record.evidenceFingerprint().isBlank()
                : record.evidenceFingerprint().equals(attestation.aggregateEvidenceFingerprint());
        boolean generationMatches = record.evidence() instanceof TestSuiteRunEvidenceV2 semantic
                ? TestSuiteRunEvidenceV2.SCHEMA_VERSION.equals(semantic.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION_V2.equals(attestation.schemaVersion())
                : record.evidence() instanceof TestSuiteRunEvidence structural
                && TestSuiteRunEvidence.SCHEMA_VERSION.equals(structural.schemaVersion())
                && TestSuiteRunAttestation.SCHEMA_VERSION.equals(attestation.schemaVersion());
        if ((!verified && !unavailableTerminal) || !scopeMatches
                || !identityMatches || !fingerprintMatches || !generationMatches) {
            throw new IllegalArgumentException(
                    "Suite-run persistence requires a structurally valid signed attestation");
        }
    }

    private static void requireLease(TestSuiteRunLease lease, Instant observedAt) {
        requireLeaseValue(lease);
        if (observedAt == null || !lease.expiresAt().isAfter(observedAt)) {
            throw new IllegalArgumentException("Suite-run lease must expire after the observation time");
        }
    }

    private static void requireLeaseValue(TestSuiteRunLease lease) {
        if (lease == null) {
            throw new IllegalArgumentException("Suite-run lease is required");
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
