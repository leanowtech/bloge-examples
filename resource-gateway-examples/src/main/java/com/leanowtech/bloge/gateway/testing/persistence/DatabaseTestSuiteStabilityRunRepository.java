package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JDBC execution-fence and terminal store for signed suite-stability analyses.
 *
 * <p>Scope and idempotency columns are selected before JSON deserialization. The table contains no
 * child payload, fixture value, context, or output; source evidence remains in its governed stores.
 * A fixed-cardinality lock stripe serializes claim, renewal, terminal publication, and orphan
 * cleanup for one scoped parent identity. Terminal insert and lease consumption share one local
 * transaction, so an expired or superseded owner cannot publish evidence.</p>
 */
public final class DatabaseTestSuiteStabilityRunRepository
        implements TestSuiteStabilityRunRepository {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int LOCK_STRIPES = 4_096;
    private static final int MAXIMUM_PURGE_BATCH = 10_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate mutations;

    /**
     * @param jdbc isolated test-runtime JDBC adapter
     * @param objectMapper canonical protocol mapper
     */
    public DatabaseTestSuiteStabilityRunRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this(jdbc, objectMapper, localTransactionManager(jdbc));
    }

    /**
     * @param jdbc isolated test-runtime JDBC adapter
     * @param objectMapper canonical protocol mapper
     * @param transactionManager transaction manager for the same datasource
     */
    public DatabaseTestSuiteStabilityRunRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        mutations = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the terminal stability table and scoped lookup indexes when absent. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_execution_locks (
                    lock_key VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_execution_leases (
                    stability_run_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_execution_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_execution_expiry
                ON rg_test_suite_stability_execution_leases (
                    lease_expires_at, stability_run_id
                )
                """);
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
    public Instant currentTime() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Stability database did not return current time");
        }
        return value.toInstant();
    }

    @Override
    public TestSuiteStabilityLeaseClaim claim(TestSuiteStabilityLeaseRequest request) {
        Objects.requireNonNull(request, "request");
        TestSuiteStabilityLeaseClaim result = mutations.execute(status -> {
            lock(request.tenantId(), request.environmentId(), request.clientRequestId());
            Instant observedAt = currentTime();
            Optional<TestSuiteStabilityRunRecord> terminal = terminalByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId());
            if (terminal.isPresent()) {
                TestSuiteStabilityRunRecord existing = terminal.get();
                requireSameTerminalIntent(existing, request);
                if (!existing.expiresAt().isAfter(observedAt)) {
                    throw conflict(TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_RETIRED,
                            "Stability idempotency identity is retained after evidence expiry");
                }
                return TestSuiteStabilityLeaseClaim.completed(existing);
            }

            Optional<TestSuiteStabilityExecutionLease> stored = leaseByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId());
            long epoch = 0;
            Instant expiresAt = observedAt.plus(request.leaseDuration());
            if (stored.isPresent()) {
                TestSuiteStabilityExecutionLease existing = stored.get();
                requireSameLeaseIntent(existing, request);
                if (existing.expiresAt().isAfter(observedAt)) {
                    return TestSuiteStabilityLeaseClaim.inProgress(
                            retryAfterSeconds(existing.expiresAt(), observedAt));
                }
                epoch = Math.addExact(existing.epoch(), 1);
                int updated = jdbc.update("""
                        UPDATE rg_test_suite_stability_execution_leases
                        SET owner_id = ?, lease_epoch = ?, lease_expires_at = ?, updated_at = ?
                        WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                          AND client_request_id = ? AND request_fingerprint = ?
                          AND owner_id = ? AND lease_epoch = ? AND lease_expires_at = ?
                        """, request.ownerId(), epoch, Timestamp.from(expiresAt),
                        Timestamp.from(observedAt), existing.stabilityRunId(), existing.tenantId(),
                        existing.environmentId(), existing.clientRequestId(),
                        existing.requestFingerprint(), existing.ownerId(), existing.epoch(),
                        Timestamp.from(existing.expiresAt()));
                if (updated != 1) {
                    throw new IllegalStateException(
                            "Locked stability lease takeover lost its exact fence");
                }
            } else {
                try {
                    int inserted = jdbc.update("""
                            INSERT INTO rg_test_suite_stability_execution_leases (
                                stability_run_id, tenant_id, environment_id, client_request_id,
                                request_fingerprint, owner_id, lease_epoch, lease_expires_at,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, request.stabilityRunId(), request.tenantId(),
                            request.environmentId(), request.clientRequestId(),
                            request.requestFingerprint(), request.ownerId(), epoch,
                            Timestamp.from(expiresAt), Timestamp.from(observedAt),
                            Timestamp.from(observedAt));
                    if (inserted != 1) {
                        throw new IllegalStateException(
                                "Stability lease insert did not create exactly one row");
                    }
                } catch (DuplicateKeyException collision) {
                    throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                            "Deterministic stability identity already belongs to another row");
                }
            }
            return TestSuiteStabilityLeaseClaim.acquired(new TestSuiteStabilityExecutionLease(
                    request.stabilityRunId(), request.tenantId(), request.environmentId(),
                    request.clientRequestId(), request.requestFingerprint(), request.ownerId(),
                    epoch, expiresAt));
        });
        if (result == null) {
            throw new IllegalStateException("Stability lease claim returned no result");
        }
        return result;
    }

    @Override
    public Optional<TestSuiteStabilityExecutionLease> renew(
            TestSuiteStabilityExecutionLease lease,
            Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease");
        Duration bounded = boundedLease(leaseDuration);
        Optional<TestSuiteStabilityExecutionLease> result = mutations.execute(status -> {
            lock(lease.tenantId(), lease.environmentId(), lease.clientRequestId());
            Instant observedAt = currentTime();
            Optional<TestSuiteStabilityExecutionLease> stored = leaseByRunId(
                    lease.tenantId(), lease.environmentId(), lease.stabilityRunId());
            if (stored.isEmpty() || !sameFence(stored.get(), lease)
                    || !stored.get().expiresAt().isAfter(observedAt)) {
                return Optional.empty();
            }
            Instant expiresAt = observedAt.plus(bounded);
            int updated = jdbc.update("""
                    UPDATE rg_test_suite_stability_execution_leases
                    SET lease_expires_at = ?, updated_at = ?
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                      AND owner_id = ? AND lease_epoch = ? AND lease_expires_at = ?
                    """, Timestamp.from(expiresAt), Timestamp.from(observedAt),
                    lease.stabilityRunId(), lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId(), lease.requestFingerprint(), lease.ownerId(),
                    lease.epoch(), Timestamp.from(stored.get().expiresAt()));
            if (updated != 1) {
                return Optional.empty();
            }
            return Optional.of(new TestSuiteStabilityExecutionLease(
                    lease.stabilityRunId(), lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId(), lease.requestFingerprint(), lease.ownerId(),
                    lease.epoch(), expiresAt));
        });
        return result == null ? Optional.empty() : result;
    }

    @Override
    public boolean release(TestSuiteStabilityExecutionLease lease) {
        Objects.requireNonNull(lease, "lease");
        Boolean released = mutations.execute(status -> {
            lock(lease.tenantId(), lease.environmentId(), lease.clientRequestId());
            Instant observedAt = currentTime();
            return jdbc.update("""
                    DELETE FROM rg_test_suite_stability_execution_leases
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                      AND owner_id = ? AND lease_epoch = ? AND lease_expires_at > ?
                    """, lease.stabilityRunId(), lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId(), lease.requestFingerprint(), lease.ownerId(),
                    lease.epoch(), Timestamp.from(observedAt)) == 1;
        });
        return Boolean.TRUE.equals(released);
    }

    @Override
    public TestSuiteStabilityRunRecord complete(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease) {
        requireComplete(record);
        requireRecordMatchesLease(record, lease);
        TestSuiteStabilityRunRecord completed = mutations.execute(status -> {
            lock(lease.tenantId(), lease.environmentId(), lease.clientRequestId());
            Instant observedAt = currentTime();
            Optional<TestSuiteStabilityExecutionLease> stored = leaseByRunId(
                    lease.tenantId(), lease.environmentId(), lease.stabilityRunId());
            if (stored.isEmpty() || !sameFence(stored.get(), lease)
                    || !stored.get().expiresAt().isAfter(observedAt)) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.LEASE_LOST,
                        "Suite-stability terminal publication requires a live exact lease");
            }
            insertTerminal(record);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_execution_leases
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                      AND owner_id = ? AND lease_epoch = ? AND lease_expires_at = ?
                    """, lease.stabilityRunId(), lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId(), lease.requestFingerprint(), lease.ownerId(),
                    lease.epoch(), Timestamp.from(stored.get().expiresAt()));
            if (deleted != 1) {
                throw new IllegalStateException(
                        "Stability terminal insert did not consume its exact lease");
            }
            return record;
        });
        if (completed == null) {
            throw new IllegalStateException("Stability completion returned no record");
        }
        return completed;
    }

    @Override
    public int purgeExpiredLeases(int limit) {
        if (limit < 1 || limit > MAXIMUM_PURGE_BATCH) {
            throw new IllegalArgumentException("Stability lease purge limit is outside bounds");
        }
        Integer deleted = mutations.execute(status -> {
            Instant observedAt = currentTime();
            List<LeaseCandidate> candidates = jdbc.query("""
                    SELECT stability_run_id, tenant_id, environment_id, client_request_id
                    FROM rg_test_suite_stability_execution_leases
                    WHERE lease_expires_at <= ?
                    ORDER BY lease_expires_at, stability_run_id
                    LIMIT ?
                    """, (rs, row) -> new LeaseCandidate(
                    rs.getString("stability_run_id"), rs.getString("tenant_id"),
                    rs.getString("environment_id"), rs.getString("client_request_id")),
                    Timestamp.from(observedAt), limit);
            candidates.stream().map(candidate -> lockKey(candidate.tenantId(),
                            candidate.environmentId(), candidate.clientRequestId()))
                    .distinct().sorted().forEach(this::lockKey);
            int removed = 0;
            for (LeaseCandidate candidate : candidates) {
                removed += jdbc.update("""
                        DELETE FROM rg_test_suite_stability_execution_leases
                        WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                          AND client_request_id = ? AND lease_expires_at <= ?
                        """, candidate.stabilityRunId(), candidate.tenantId(),
                        candidate.environmentId(), candidate.clientRequestId(),
                        Timestamp.from(observedAt));
            }
            return removed;
        });
        return deleted == null ? 0 : deleted;
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

    private Optional<TestSuiteStabilityRunRecord> terminalByClientRequestId(
            String tenantId,
            String environmentId,
            String clientRequestId) {
        return query("""
                SELECT record_json FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, tenantId, environmentId, clientRequestId);
    }

    private Optional<TestSuiteStabilityExecutionLease> leaseByClientRequestId(
            String tenantId,
            String environmentId,
            String clientRequestId) {
        return lease("""
                SELECT stability_run_id, tenant_id, environment_id, client_request_id,
                       request_fingerprint, owner_id, lease_epoch, lease_expires_at
                FROM rg_test_suite_stability_execution_leases
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, tenantId, environmentId, clientRequestId);
    }

    private Optional<TestSuiteStabilityExecutionLease> leaseByRunId(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        return lease("""
                SELECT stability_run_id, tenant_id, environment_id, client_request_id,
                       request_fingerprint, owner_id, lease_epoch, lease_expires_at
                FROM rg_test_suite_stability_execution_leases
                WHERE tenant_id = ? AND environment_id = ? AND stability_run_id = ?
                """, tenantId, environmentId, stabilityRunId);
    }

    private Optional<TestSuiteStabilityExecutionLease> lease(String sql, Object... arguments) {
        List<TestSuiteStabilityExecutionLease> leases = jdbc.query(sql, (rs, row) ->
                new TestSuiteStabilityExecutionLease(
                        rs.getString("stability_run_id"), rs.getString("tenant_id"),
                        rs.getString("environment_id"), rs.getString("client_request_id"),
                        rs.getString("request_fingerprint"), rs.getString("owner_id"),
                        rs.getLong("lease_epoch"),
                        rs.getTimestamp("lease_expires_at").toInstant()), arguments);
        return leases.stream().findFirst();
    }

    private void insertTerminal(TestSuiteStabilityRunRecord record) {
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
        } catch (DuplicateKeyException duplicate) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                    "Stability analysis id or scoped idempotency identity already exists");
        }
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

    private static void requireRecordMatchesLease(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease) {
        if (lease == null
                || !record.stabilityRunId().equals(lease.stabilityRunId())
                || !record.tenantId().equals(lease.tenantId())
                || !record.environmentId().equals(lease.environmentId())
                || !record.clientRequestId().equals(lease.clientRequestId())
                || !record.requestFingerprint().equals(lease.requestFingerprint())) {
            throw new IllegalArgumentException(
                    "Terminal stability record must match its exact execution lease");
        }
    }

    private static void requireSameTerminalIntent(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityLeaseRequest request) {
        requireCompleteCoordinates(record.stabilityRunId(), record.requestFingerprint(),
                request.stabilityRunId(), request.requestFingerprint());
    }

    private static void requireSameLeaseIntent(
            TestSuiteStabilityExecutionLease lease,
            TestSuiteStabilityLeaseRequest request) {
        requireCompleteCoordinates(lease.stabilityRunId(), lease.requestFingerprint(),
                request.stabilityRunId(), request.requestFingerprint());
    }

    private static void requireCompleteCoordinates(
            String storedRunId,
            String storedFingerprint,
            String requestedRunId,
            String requestedFingerprint) {
        if (!storedRunId.equals(requestedRunId)
                || !storedFingerprint.equals(requestedFingerprint)) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT,
                    "Suite-stability idempotency identity represents different intent");
        }
    }

    private static boolean sameFence(
            TestSuiteStabilityExecutionLease stored,
            TestSuiteStabilityExecutionLease requested) {
        return stored.stabilityRunId().equals(requested.stabilityRunId())
                && stored.tenantId().equals(requested.tenantId())
                && stored.environmentId().equals(requested.environmentId())
                && stored.clientRequestId().equals(requested.clientRequestId())
                && stored.requestFingerprint().equals(requested.requestFingerprint())
                && stored.ownerId().equals(requested.ownerId())
                && stored.epoch() == requested.epoch();
    }

    private void lock(String tenantId, String environmentId, String clientRequestId) {
        lockKey(lockKey(tenantId, environmentId, clientRequestId));
    }

    private void lockKey(String lockKey) {
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_execution_locks (lock_key)
                KEY(lock_key) VALUES (?)
                """, lockKey);
        List<String> locked = jdbc.query("""
                SELECT lock_key FROM rg_test_suite_stability_execution_locks
                WHERE lock_key = ? FOR UPDATE
                """, (rs, row) -> rs.getString("lock_key"), lockKey);
        if (locked.size() != 1) {
            throw new IllegalStateException("Stability execution lock is unavailable");
        }
    }

    private static String lockKey(
            String tenantId,
            String environmentId,
            String clientRequestId) {
        String identity = ProtocolFingerprint.ofText(
                "bloge.testSuiteStabilityLeaseIdentity.v1|" + tenantId + '|'
                        + environmentId + '|' + clientRequestId);
        int stripe = Math.floorMod(identity.hashCode(), LOCK_STRIPES);
        return ProtocolFingerprint.ofText(
                "bloge.testSuiteStabilityLeaseLockStripe.v1|" + stripe);
    }

    private static Duration boundedLease(Duration value) {
        if (value == null
                || value.compareTo(TestSuiteStabilityLeaseRequest.MINIMUM_LEASE) < 0
                || value.compareTo(TestSuiteStabilityLeaseRequest.MAXIMUM_LEASE) > 0
                || value.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "Stability lease duration is outside bounded whole-second limits");
        }
        return value;
    }

    private static long retryAfterSeconds(Instant expiresAt, Instant observedAt) {
        long millis = Duration.between(observedAt, expiresAt).toMillis();
        return Math.max(1, Math.min(3_600, Math.floorDiv(millis + 999, 1_000)));
    }

    private static TestSuiteStabilityRunConflictException conflict(
            TestSuiteStabilityRunConflictException.Reason reason,
            String message) {
        return new TestSuiteStabilityRunConflictException(reason, message);
    }

    private static List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> sourceRefs(
            TestSuiteStabilityRunRecord record) {
        return record.evidence().attempts().stream()
                .filter(value -> !value.suiteRunId().isBlank()
                        && !value.aggregateEvidenceFingerprint().isBlank())
                .map(value -> new TestSuiteStabilityAttestation.SourceSuiteEvidenceRef(
                        value.attempt(), value.suiteRunId(),
                        value.aggregateEvidenceFingerprint(), value.sourcePromotionStatus(),
                        value.sourcePromotionReasons()))
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

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("Stability JDBC adapter requires a datasource");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }

    private record LeaseCandidate(
            String stabilityRunId,
            String tenantId,
            String environmentId,
            String clientRequestId) {
    }
}
