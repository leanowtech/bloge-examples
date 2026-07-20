package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.AbandonedTestSuiteRun;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecordIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRepository;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    private final TestSuiteRunAttestationService attestations;

    /** Creates a repository over the isolated test-runtime JDBC store. */
    public DatabaseTestSuiteRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                          TestSuiteRunAttestationService attestations) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
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
        TestSuiteRunRecord snapshot = TestSuiteRunRecordIntegrity.verifiedWriteSnapshot(
                objectMapper, attestations, record);
        requireLeaseValue(lease);
        try {
            int rows = jdbc.update("""
                    INSERT INTO rg_test_suite_run_records (
                        suite_run_id, tenant_id, environment_id, client_request_id,
                        suite_id, suite_revision, status, evidence_fingerprint,
                        checkpoint_version, lease_owner, lease_expires_at, last_checkpoint_at,
                        created_at, expires_at, record_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                    """, snapshot.suiteRunId(), snapshot.tenantId(), snapshot.environmentId(),
                    snapshot.clientRequestId(), snapshot.evidence().suiteRef().suiteId(),
                    snapshot.evidence().suiteRef().revision(), snapshot.evidence().status().name(),
                    snapshot.evidenceFingerprint(), lease.ownerId(), Timestamp.from(lease.expiresAt()),
                    Timestamp.from(snapshot.createdAt()), Timestamp.from(snapshot.createdAt()),
                    Timestamp.from(snapshot.expiresAt()), write(snapshot));
            if (rows != 1) {
                throw new IllegalStateException("Suite-run insert did not create exactly one row");
            }
            return snapshot;
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
        TestSuiteRunRecord snapshot = TestSuiteRunRecordIntegrity.verifiedWriteSnapshot(
                objectMapper, attestations, record);
        requireLease(lease, observedAt);
        int rows = jdbc.update("""
                UPDATE rg_test_suite_run_records
                SET status = ?, evidence_fingerprint = ?, record_json = ?,
                    checkpoint_version = checkpoint_version + 1,
                    lease_expires_at = ?, last_checkpoint_at = ?
                WHERE suite_run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND client_request_id = ? AND status = 'RUNNING'
                  AND lease_owner = ? AND lease_expires_at > ? AND expires_at > ?
                """, snapshot.evidence().status().name(), snapshot.evidenceFingerprint(), write(snapshot),
                Timestamp.from(lease.expiresAt()), Timestamp.from(observedAt),
                snapshot.suiteRunId(), snapshot.tenantId(), snapshot.environmentId(),
                snapshot.clientRequestId(),
                lease.ownerId(), Timestamp.from(observedAt), Timestamp.from(observedAt));

        if (rows != 1) {
            throw new IllegalStateException("Suite-run lease was lost or checkpoint is already terminal");
        }
        return snapshot;
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
        List<AbandonedRow> rows = jdbc.query("""
                SELECT suite_run_id, tenant_id, environment_id, client_request_id,
                       suite_id, suite_revision, status, evidence_fingerprint,
                       created_at, expires_at, record_json,
                       checkpoint_version, lease_owner, lease_expires_at
                FROM rg_test_suite_run_records
                WHERE status = 'RUNNING' AND lease_expires_at <= ? AND expires_at > ?
                ORDER BY lease_expires_at, suite_run_id
                LIMIT ?
                """, (rs, row) -> new AbandonedRow(storedRow(rs),
                        rs.getLong("checkpoint_version"), rs.getString("lease_owner"),
                        rs.getTimestamp("lease_expires_at").toInstant()),
                Timestamp.from(observedAt), Timestamp.from(observedAt), boundedLimit);
        return rows.stream().map(row -> new AbandonedTestSuiteRun(
                verifyStoredRow(row.stored()), row.checkpointVersion(), row.leaseOwner(),
                row.leaseExpiresAt())).toList();
    }

    @Override
    public boolean reconcileAbandoned(AbandonedTestSuiteRun abandoned, TestSuiteRunRecord terminal,
                                      Instant observedAt) {
        TestSuiteRunRecord snapshot = TestSuiteRunRecordIntegrity.verifiedWriteSnapshot(
                objectMapper, attestations, terminal);
        AbandonedTestSuiteRun candidate = TestSuiteRunRecordIntegrity.verifiedAbandoned(
                objectMapper, attestations, abandoned, observedAt);
        if (!candidate.record().suiteRunId().equals(snapshot.suiteRunId())
                || snapshot.evidence().status() == com.leanowtech.bloge.gateway.testing.domain
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
                """, snapshot.evidence().status().name(), snapshot.evidenceFingerprint(), write(snapshot),
                Timestamp.from(observedAt), snapshot.suiteRunId(), snapshot.tenantId(),
                snapshot.environmentId(), candidate.checkpointVersion(), candidate.leaseOwner(),
                Timestamp.from(observedAt), Timestamp.from(observedAt)) == 1;
    }

    @Override
    public Optional<TestSuiteRunRecord> find(String tenantId, String environmentId, String suiteRunId) {
        return queryRows("""
                        SELECT suite_run_id, tenant_id, environment_id, client_request_id,
                               suite_id, suite_revision, status, evidence_fingerprint,
                               created_at, expires_at, record_json
                        FROM rg_test_suite_run_records
                        WHERE tenant_id = ? AND environment_id = ? AND suite_run_id = ?
                          AND expires_at > CURRENT_TIMESTAMP
                        """, tenantId, environmentId, suiteRunId).stream().findFirst()
                .map(this::verifyStoredRow)
                .map(record -> TestSuiteRunRecordIntegrity.verifiedRunLookup(
                        objectMapper, attestations, record, tenantId, environmentId, suiteRunId));
    }

    @Override
    public Optional<TestSuiteRunRecord> findByClientRequestId(
            String tenantId, String environmentId, String clientRequestId) {
        return queryRows("""
                        SELECT suite_run_id, tenant_id, environment_id, client_request_id,
                               suite_id, suite_revision, status, evidence_fingerprint,
                               created_at, expires_at, record_json
                        FROM rg_test_suite_run_records
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                          AND expires_at > CURRENT_TIMESTAMP
                """, tenantId, environmentId, clientRequestId).stream().findFirst()
                .map(this::verifyStoredRow)
                .map(record -> TestSuiteRunRecordIntegrity.verifiedClientLookup(
                        objectMapper, attestations, record, tenantId, environmentId, clientRequestId));
    }

    /** {@inheritDoc} */
    @Override
    public List<TestSuiteRunRecord> findTerminalBySuite(
            String tenantId, String environmentId, String suiteId, long revision, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 1001));
        return queryRows("""
                        SELECT suite_run_id, tenant_id, environment_id, client_request_id,
                               suite_id, suite_revision, status, evidence_fingerprint,
                               created_at, expires_at, record_json
                        FROM rg_test_suite_run_records
                        WHERE tenant_id = ? AND environment_id = ?
                          AND suite_id = ? AND suite_revision = ?
                          AND status <> 'RUNNING' AND expires_at > CURRENT_TIMESTAMP
                        ORDER BY created_at DESC, suite_run_id
                        LIMIT ?
                        """, tenantId, environmentId, suiteId, revision, boundedLimit).stream()
                .map(this::verifyStoredRow)
                .map(record -> TestSuiteRunRecordIntegrity.verifiedSuiteLookup(
                        objectMapper, attestations, record, tenantId, environmentId,
                        suiteId, revision))
                .toList();
    }

    private List<StoredRow> queryRows(String sql, Object... arguments) {
        return jdbc.query(sql, (rs, row) -> storedRow(rs), arguments);
    }

    private StoredRow storedRow(ResultSet rs) throws SQLException {
        return new StoredRow(read(rs.getString("record_json")), rs.getString("suite_run_id"),
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("client_request_id"), rs.getString("suite_id"),
                rs.getLong("suite_revision"), rs.getString("status"),
                rs.getString("evidence_fingerprint"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }

    private TestSuiteRunRecord verifyStoredRow(StoredRow stored) {
        TestSuiteRunRecord snapshot = TestSuiteRunRecordIntegrity.verifiedSnapshot(
                objectMapper, attestations, stored.record());
        if (!Objects.equals(stored.suiteRunId(), snapshot.suiteRunId())
                || !Objects.equals(stored.tenantId(), snapshot.tenantId())
                || !Objects.equals(stored.environmentId(), snapshot.environmentId())
                || !Objects.equals(stored.clientRequestId(), snapshot.clientRequestId())
                || !Objects.equals(stored.suiteId(), snapshot.evidence().suiteRef().suiteId())
                || stored.suiteRevision() != snapshot.evidence().suiteRef().revision()
                || !Objects.equals(stored.status(), snapshot.evidence().status().name())
                || !Objects.equals(stored.evidenceFingerprint(), snapshot.evidenceFingerprint())
                || !Objects.equals(stored.createdAt(), snapshot.createdAt())
                || !Objects.equals(stored.expiresAt(), snapshot.expiresAt())) {
            throw new TestSuiteRunIntegrityException();
        }
        return snapshot;
    }

    private record StoredRow(TestSuiteRunRecord record, String suiteRunId, String tenantId,
                             String environmentId, String clientRequestId, String suiteId,
                             long suiteRevision, String status, String evidenceFingerprint,
                             Instant createdAt, Instant expiresAt) {
    }

    private record AbandonedRow(StoredRow stored, long checkpointVersion, String leaseOwner,
                                Instant leaseExpiresAt) {
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
            throw new TestSuiteRunIntegrityException(failure);
        }
    }
}
