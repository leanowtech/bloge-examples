package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionProgress;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStopRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityCrossRetentionTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityHistoryWindow;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationArchiveSegment;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerEntry;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerHead;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerLifecyclePageRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerRange;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityProgressCheckpoint;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityProgressSnapshot;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationFloorRetirementEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationFloorRetirementIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerFloorIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationProjector;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerEntryIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerHeadIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerLifecyclePageIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationLedgerRangeIntegrity;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * A fixed-cardinality lock stripe serializes claim, renewal, stop, terminal publication, and
 * orphan cleanup for one scoped parent identity. A separate exact-suite lock serializes compact
 * observation sequence and predecessor assignment across replicas. Terminal insert, signed
 * observation append, head advance, progress deletion, and lease consumption share one local
 * transaction, while a retained payload-free stop atomically consumes resumable progress; an
 * expired, superseded, or stopped owner therefore cannot publish evidence.</p>
 */
public final class DatabaseTestSuiteStabilityRunRepository
        implements TestSuiteStabilityRunRepository {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int LOCK_STRIPES = 4_096;
    private static final int MAXIMUM_PURGE_BATCH = 10_000;
    private static final TypeReference<List<TestSuiteStabilityExecutionProgress.AttemptReference>>
            ATTEMPT_REFERENCES = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate mutations;
    private final TestSuiteStabilityObservationProjector observationProjector;

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
        this.observationProjector = new TestSuiteStabilityObservationProjector(objectMapper);
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
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_progress (
                    stability_run_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    suite_revision BIGINT NOT NULL,
                    suite_fingerprint VARCHAR(71) NOT NULL,
                    classification VARCHAR(32) NOT NULL,
                    planned_attempts INTEGER NOT NULL,
                    completed_attempts INTEGER NOT NULL,
                    attempts_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_progress_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_progress_retention
                ON rg_test_suite_stability_progress (expires_at, stability_run_id)
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
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_execution_stops (
                    stability_run_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    reason VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    stop_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_stop_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_stop_retention
                ON rg_test_suite_stability_execution_stops (expires_at, stability_run_id)
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
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observation_locks (
                    scope_fingerprint VARCHAR(71) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observation_heads (
                    scope_fingerprint VARCHAR(71) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    suite_revision BIGINT NOT NULL,
                    suite_fingerprint VARCHAR(71) NOT NULL,
                    coverage_from TIMESTAMP WITH TIME ZONE NOT NULL,
                    latest_sequence BIGINT NOT NULL,
                    latest_observation_id VARCHAR(255) NOT NULL,
                    latest_entry_fingerprint VARCHAR(71) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    head_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observations (
                    observation_id VARCHAR(255) PRIMARY KEY,
                    scope_fingerprint VARCHAR(71) NOT NULL,
                    ledger_sequence BIGINT NOT NULL,
                    previous_observation_id VARCHAR(255) NOT NULL,
                    stability_run_id VARCHAR(255) NOT NULL,
                    source_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    appended_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    observation_fingerprint VARCHAR(71) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    entry_fingerprint VARCHAR(71) NOT NULL,
                    entry_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_observation_run
                        UNIQUE (stability_run_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_sequence
                        UNIQUE (scope_fingerprint, ledger_sequence)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_observation_scope
                ON rg_test_suite_stability_observations (
                    scope_fingerprint, ledger_sequence
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_observation_time
                ON rg_test_suite_stability_observations (
                    scope_fingerprint, appended_at, ledger_sequence
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observation_floors (
                    scope_fingerprint VARCHAR(71) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    suite_revision BIGINT NOT NULL,
                    suite_fingerprint VARCHAR(71) NOT NULL,
                    floor_sequence BIGINT NOT NULL,
                    previous_observation_id VARCHAR(255) NOT NULL,
                    previous_entry_fingerprint VARCHAR(71) NOT NULL,
                    floor_observation_id VARCHAR(255) NOT NULL,
                    floor_entry_fingerprint VARCHAR(71) NOT NULL,
                    coverage_from TIMESTAMP WITH TIME ZONE NOT NULL,
                    retirement_generation BIGINT NOT NULL,
                    latest_retirement_id VARCHAR(255) NOT NULL,
                    latest_retirement_fingerprint VARCHAR(71) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    floor_fingerprint VARCHAR(71) NOT NULL,
                    floor_json CLOB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observation_archive_receipts (
                    receipt_set_id VARCHAR(255) PRIMARY KEY,
                    retirement_id VARCHAR(255) NOT NULL,
                    scope_fingerprint VARCHAR(71) NOT NULL,
                    retirement_generation BIGINT NOT NULL,
                    segment_id VARCHAR(255) NOT NULL,
                    retirement_fingerprint VARCHAR(71) NOT NULL,
                    segment_fingerprint VARCHAR(71) NOT NULL,
                    retention_policy_fingerprint VARCHAR(71) NOT NULL,
                    retain_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    required_copies INTEGER NOT NULL,
                    receipt_count INTEGER NOT NULL,
                    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    receipt_set_fingerprint VARCHAR(71) NOT NULL,
                    receipt_set_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_observation_archive_receipt_retirement
                        UNIQUE (retirement_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_archive_receipt_generation
                        UNIQUE (scope_fingerprint, retirement_generation)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_archive_receipt_scope
                ON rg_test_suite_stability_observation_archive_receipts (
                    scope_fingerprint, retirement_generation
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observation_archives (
                    segment_id VARCHAR(255) PRIMARY KEY,
                    scope_fingerprint VARCHAR(71) NOT NULL,
                    retirement_generation BIGINT NOT NULL,
                    from_sequence BIGINT NOT NULL,
                    through_sequence BIGINT NOT NULL,
                    segment_fingerprint VARCHAR(71) NOT NULL,
                    archived_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    segment_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_observation_archive_generation
                        UNIQUE (scope_fingerprint, retirement_generation)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_observation_retirements (
                    retirement_id VARCHAR(255) PRIMARY KEY,
                    scope_fingerprint VARCHAR(71) NOT NULL,
                    retirement_generation BIGINT NOT NULL,
                    evidence_fingerprint VARCHAR(71) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    retirement_fingerprint VARCHAR(71) NOT NULL,
                    retired_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    retirement_json CLOB NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_observation_retirement_generation
                        UNIQUE (scope_fingerprint, retirement_generation)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_observation_retirement_scope
                ON rg_test_suite_stability_observation_retirements (
                    scope_fingerprint, retirement_generation
                )
                """);
        backfillObservationFloors();
    }

    private void backfillObservationFloors() {
        List<StoredObservationHead> heads = jdbc.query("""
                SELECT tenant_id, environment_id, scope_fingerprint,
                       suite_id, suite_revision, suite_fingerprint,
                       coverage_from, latest_sequence, latest_observation_id,
                       latest_entry_fingerprint, updated_at, head_fingerprint
                FROM rg_test_suite_stability_observation_heads
                ORDER BY scope_fingerprint
                """, (rs, row) -> new StoredObservationHead(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                readObservationHead(rs)));
        for (StoredObservationHead stored : heads) {
            mutations.executeWithoutResult(status -> {
                String scopeFingerprint = stored.head().scopeFingerprint();
                lockObservationScope(scopeFingerprint);
                Long count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM rg_test_suite_stability_observation_floors
                        WHERE scope_fingerprint = ?
                        """, Long.class, scopeFingerprint);
                if (count != null && count > 0) {
                    return;
                }
                TestSuiteStabilityObservationLedgerEntry first = observationEntryAt(
                        scopeFingerprint, 1L).orElseThrow(() ->
                        new IllegalStateException(
                                "Legacy stability observation head has no rollout entry"));
                requireObservationEntry(first, scopeFingerprint, 1L, "");
                if (!stored.head().coverageFrom().equals(first.appendedAt())) {
                    throw new IllegalStateException(
                            "Legacy stability observation head has an ambiguous rollout floor");
                }
                insertRolloutFloor(
                        stored.tenantId(), stored.environmentId(), first);
            });
        }
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

            Optional<TestSuiteStabilityExecutionStop> stop = stopByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId())
                    .or(() -> stopByRunId(request.tenantId(), request.environmentId(),
                            request.stabilityRunId()));
            if (stop.isPresent()) {
                TestSuiteStabilityExecutionStop existing = stop.get();
                requireSameStopIntent(existing, request);
                if (!existing.expiresAt().isAfter(observedAt)) {
                    throw conflict(TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_RETIRED,
                            "Stopped stability identity is retained after stop expiry");
                }
                return TestSuiteStabilityLeaseClaim.stopped(existing);
            }

            TestSuiteStabilityExecutionProgress progress = progressByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId())
                    .map(existing -> {
                        requireSameProgressIntent(existing, request);
                        if (!existing.expiresAt().isAfter(observedAt)) {
                            throw conflict(
                                    TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_RETIRED,
                                    "Stability progress identity is retained after recovery expiry");
                        }
                        return existing;
                    })
                    .orElseGet(() -> createProgress(request, observedAt));

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
            return TestSuiteStabilityLeaseClaim.acquired(
                    new TestSuiteStabilityExecutionLease(
                            request.stabilityRunId(), request.tenantId(), request.environmentId(),
                            request.clientRequestId(), request.requestFingerprint(),
                            request.ownerId(), epoch, expiresAt),
                    progress);
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
    public TestSuiteStabilityProgressCheckpoint checkpoint(
            TestSuiteStabilityExecutionLease lease,
            TestSuiteStabilityExecutionProgress.AttemptReference attempt,
            Duration leaseDuration,
            Duration progressRetention) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(attempt, "attempt");
        Duration boundedLease = boundedLease(leaseDuration);
        Duration boundedRetention = boundedProgressRetention(progressRetention);
        TestSuiteStabilityProgressCheckpoint checkpoint = mutations.execute(status -> {
            lock(lease.tenantId(), lease.environmentId(), lease.clientRequestId());
            Instant observedAt = currentTime();
            TestSuiteStabilityExecutionLease storedLease = leaseByRunId(
                    lease.tenantId(), lease.environmentId(), lease.stabilityRunId())
                    .filter(value -> sameFence(value, lease)
                            && value.expiresAt().isAfter(observedAt))
                    .orElseThrow(() -> conflict(
                            TestSuiteStabilityRunConflictException.Reason.LEASE_LOST,
                            "Suite-stability progress requires a live exact lease"));
            TestSuiteStabilityExecutionProgress storedProgress = progressByRunId(
                    lease.tenantId(), lease.environmentId(), lease.stabilityRunId())
                    .orElseThrow(() -> conflict(
                            TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                            "Suite-stability durable progress is missing"));
            requireProgressMatchesLease(storedProgress, lease);
            if (!storedProgress.expiresAt().isAfter(observedAt)) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                        "Suite-stability durable progress has expired");
            }

            Instant progressExpiresAt = observedAt.plus(boundedRetention);
            TestSuiteStabilityExecutionProgress successor;
            try {
                successor = storedProgress.append(attempt, observedAt, progressExpiresAt);
            } catch (IllegalArgumentException rejected) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                        "Suite-stability progress append is non-contiguous or contradictory");
            }
            int progressUpdated = jdbc.update("""
                    UPDATE rg_test_suite_stability_progress
                    SET completed_attempts = ?, attempts_json = ?, updated_at = ?, expires_at = ?
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                      AND completed_attempts = ? AND updated_at = ?
                    """, successor.completedAttempts(), writeAttempts(successor.attempts()),
                    Timestamp.from(successor.updatedAt()), Timestamp.from(successor.expiresAt()),
                    successor.stabilityRunId(), successor.tenantId(), successor.environmentId(),
                    successor.clientRequestId(), successor.requestFingerprint(),
                    storedProgress.completedAttempts(), Timestamp.from(storedProgress.updatedAt()));
            if (progressUpdated != 1) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                        "Suite-stability progress lost its exact checkpoint revision");
            }

            Instant leaseExpiresAt = observedAt.plus(boundedLease);
            int leaseUpdated = jdbc.update("""
                    UPDATE rg_test_suite_stability_execution_leases
                    SET lease_expires_at = ?, updated_at = ?
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                      AND owner_id = ? AND lease_epoch = ? AND lease_expires_at = ?
                    """, Timestamp.from(leaseExpiresAt), Timestamp.from(observedAt),
                    storedLease.stabilityRunId(), storedLease.tenantId(),
                    storedLease.environmentId(), storedLease.clientRequestId(),
                    storedLease.requestFingerprint(), storedLease.ownerId(), storedLease.epoch(),
                    Timestamp.from(storedLease.expiresAt()));
            if (leaseUpdated != 1) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.LEASE_LOST,
                        "Suite-stability progress checkpoint lost its execution fence");
            }
            TestSuiteStabilityExecutionLease renewed = new TestSuiteStabilityExecutionLease(
                    storedLease.stabilityRunId(), storedLease.tenantId(),
                    storedLease.environmentId(), storedLease.clientRequestId(),
                    storedLease.requestFingerprint(), storedLease.ownerId(), storedLease.epoch(),
                    leaseExpiresAt);
            return new TestSuiteStabilityProgressCheckpoint(renewed, successor);
        });
        if (checkpoint == null) {
            throw new IllegalStateException("Stability progress checkpoint returned no result");
        }
        return checkpoint;
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
    public TestSuiteStabilityExecutionStop stop(
            TestSuiteStabilityExecutionStopRequest request) {
        Objects.requireNonNull(request, "request");
        TestSuiteStabilityExecutionStop result = mutations.execute(status -> {
            lock(request.tenantId(), request.environmentId(), request.clientRequestId());
            Instant observedAt = currentTime();
            Optional<TestSuiteStabilityRunRecord> terminal = terminalByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId())
                    .or(() -> terminalByRunId(request.tenantId(), request.environmentId(),
                            request.stabilityRunId()));
            if (terminal.isPresent()) {
                requireCompleteCoordinates(terminal.get().stabilityRunId(),
                        terminal.get().requestFingerprint(), request.stabilityRunId(),
                        request.requestFingerprint());
                throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                        "Signed terminal stability evidence already exists");
            }
            Optional<TestSuiteStabilityExecutionStop> retained = stopByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId())
                    .or(() -> stopByRunId(request.tenantId(), request.environmentId(),
                            request.stabilityRunId()));
            if (retained.isPresent()) {
                requireSameStopIntent(retained.get(), request);
                return retained.get();
            }
            Optional<TestSuiteStabilityExecutionProgress> progress =
                    progressByClientRequestId(request.tenantId(), request.environmentId(),
                            request.clientRequestId());
            progress.ifPresent(value -> {
                requireCompleteCoordinates(value.stabilityRunId(),
                        value.requestFingerprint(), request.stabilityRunId(),
                        request.requestFingerprint());
                if (!value.classification().equals(request.classification())) {
                    throw conflict(
                            TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT,
                            "Stop classification contradicts durable stability progress");
                }
            });
            Optional<TestSuiteStabilityExecutionLease> lease =
                    leaseByClientRequestId(request.tenantId(), request.environmentId(),
                            request.clientRequestId());
            lease.ifPresent(value -> requireCompleteCoordinates(
                    value.stabilityRunId(), value.requestFingerprint(),
                    request.stabilityRunId(), request.requestFingerprint()));
            TestSuiteStabilityExecutionStop created = executionStop(request, observedAt);
            try {
                int inserted = jdbc.update("""
                        INSERT INTO rg_test_suite_stability_execution_stops (
                            stability_run_id, tenant_id, environment_id, client_request_id,
                            request_fingerprint, reason, created_at, expires_at,
                            record_fingerprint, stop_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, created.stabilityRunId(), created.tenantId(),
                        created.environmentId(), created.clientRequestId(),
                        created.requestFingerprint(), created.reason().name(),
                        Timestamp.from(created.createdAt()), Timestamp.from(created.expiresAt()),
                        created.recordFingerprint(), writeStop(created));
                if (inserted != 1) {
                    throw new IllegalStateException(
                            "Stability execution stop was not inserted exactly once");
                }
            } catch (DuplicateKeyException collision) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                        "Stability execution stop identity already belongs to another row");
            }
            int leasesDeleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_execution_leases
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                    """, request.stabilityRunId(), request.tenantId(),
                    request.environmentId(), request.clientRequestId(),
                    request.requestFingerprint());
            if (leasesDeleted != (lease.isPresent() ? 1 : 0)) {
                throw new IllegalStateException(
                        "Stability stop did not consume the exact expected lease");
            }
            int progressDeleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_progress
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                    """, request.stabilityRunId(), request.tenantId(),
                    request.environmentId(), request.clientRequestId(),
                    request.requestFingerprint());
            if (progressDeleted != (progress.isPresent() ? 1 : 0)) {
                throw new IllegalStateException(
                        "Stability stop did not consume the exact expected progress");
            }
            return created;
        });
        if (result == null) {
            throw new IllegalStateException("Stability execution stop returned no record");
        }
        return result;
    }

    @Override
    public TestSuiteStabilityRunRecord complete(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityObservation observation,
            TestSuiteStabilityExecutionLease lease) {
        requireComplete(record);
        requireObservation(record, observation);
        requireRecordMatchesLease(record, lease);
        TestSuiteStabilityRunRecord completed = mutations.execute(status -> {
            lock(lease.tenantId(), lease.environmentId(), lease.clientRequestId());
            lockObservationScope(observation.evidence().scopeFingerprint());
            Instant observedAt = currentTime();
            if (record.createdAt().isAfter(observedAt)
                    || !record.expiresAt().isAfter(observedAt)) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                        "Suite-stability terminal publication requires a live database-time record");
            }
            if (stopByClientRequestId(lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId()).isPresent()) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                        "Stopped suite-stability execution cannot publish terminal evidence");
            }
            Optional<TestSuiteStabilityExecutionLease> stored = leaseByRunId(
                    lease.tenantId(), lease.environmentId(), lease.stabilityRunId());
            if (stored.isEmpty() || !sameFence(stored.get(), lease)
                    || !stored.get().expiresAt().isAfter(observedAt)) {
                throw conflict(TestSuiteStabilityRunConflictException.Reason.LEASE_LOST,
                        "Suite-stability terminal publication requires a live exact lease");
            }
            TestSuiteStabilityExecutionProgress progress = progressByRunId(
                    lease.tenantId(), lease.environmentId(), lease.stabilityRunId())
                    .orElseThrow(() -> conflict(
                            TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                            "Suite-stability terminal publication requires durable progress"));
            requireProgressMatchesLease(progress, lease);
            requireTerminalMatchesProgress(record, progress);
            insertTerminal(record);
            appendObservation(record, observation, observedAt);
            int progressDeleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_progress
                    WHERE stability_run_id = ? AND tenant_id = ? AND environment_id = ?
                      AND client_request_id = ? AND request_fingerprint = ?
                      AND completed_attempts = ? AND updated_at = ?
                    """, progress.stabilityRunId(), progress.tenantId(),
                    progress.environmentId(), progress.clientRequestId(),
                    progress.requestFingerprint(), progress.completedAttempts(),
                    Timestamp.from(progress.updatedAt()));
            if (progressDeleted != 1) {
                throw new IllegalStateException(
                        "Stability terminal insert did not consume exact durable progress");
            }
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
    public int purgeExpiredStops(int limit) {
        if (limit < 1 || limit > MAXIMUM_PURGE_BATCH) {
            throw new IllegalArgumentException("Stability stop purge limit is outside bounds");
        }
        Integer deleted = mutations.execute(status -> {
            Instant observedAt = currentTime();
            List<TestSuiteStabilityExecutionStop> candidates = jdbc.query("""
                    SELECT * FROM rg_test_suite_stability_execution_stops
                    WHERE expires_at <= ?
                    ORDER BY expires_at, stability_run_id
                    FETCH FIRST ? ROWS ONLY
                    """, this::storedStop,
                    Timestamp.from(observedAt), limit);
            candidates.stream().map(value -> lockKey(value.tenantId(),
                            value.environmentId(), value.clientRequestId()))
                    .distinct().sorted().forEach(this::lockKey);
            int removed = 0;
            for (TestSuiteStabilityExecutionStop candidate : candidates) {
                removed += jdbc.update("""
                        DELETE FROM rg_test_suite_stability_execution_stops
                        WHERE stability_run_id = ? AND record_fingerprint = ? AND expires_at <= ?
                        """, candidate.stabilityRunId(), candidate.recordFingerprint(),
                        Timestamp.from(observedAt));
            }
            return removed;
        });
        return deleted == null ? 0 : deleted;
    }

    @Override
    public Optional<TestSuiteStabilityProgressSnapshot> findProgress(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        Optional<TestSuiteStabilityProgressSnapshot> snapshot = mutations.execute(status -> {
            Instant observedAt = currentTime();
            Optional<TestSuiteStabilityExecutionProgress> progress = progressByRunId(
                    tenantId, environmentId, stabilityRunId)
                    .filter(value -> value.expiresAt().isAfter(observedAt));
            if (progress.isEmpty()) {
                return Optional.empty();
            }
            boolean liveOwner = leaseByRunId(tenantId, environmentId, stabilityRunId)
                    .filter(value -> value.expiresAt().isAfter(observedAt))
                    .isPresent();
            return Optional.of(new TestSuiteStabilityProgressSnapshot(
                    progress.get(), liveOwner, observedAt));
        });
        return snapshot == null ? Optional.empty() : snapshot;
    }

    @Override
    public Optional<TestSuiteStabilityExecutionStop> findStop(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        List<TestSuiteStabilityExecutionStop> rows = jdbc.query("""
                SELECT * FROM rg_test_suite_stability_execution_stops
                WHERE tenant_id = ? AND environment_id = ? AND stability_run_id = ?
                  AND expires_at > CURRENT_TIMESTAMP
                """, this::storedStop,
                tenantId, environmentId, stabilityRunId);
        return rows.stream().findFirst();
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

    @Override
    public TestSuiteStabilityHistoryWindow history(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            Instant fromInclusive,
            Instant toExclusive,
            int maximumRuns) {
        Objects.requireNonNull(suiteRef, "suiteRef");
        if (fromInclusive == null || toExclusive == null
                || !fromInclusive.isBefore(toExclusive)
                || maximumRuns < TestSuiteStabilityTrendAnalysisRequest.MINIMUM_RUNS
                || maximumRuns > TestSuiteStabilityTrendAnalysisRequest.MAXIMUM_RUNS) {
            throw new IllegalArgumentException("Stability history query is outside bounds");
        }
        Instant observedAt = currentTime();
        Long totalMatchingRuns = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ?
                  AND suite_id = ? AND suite_revision = ?
                  AND created_at >= ? AND created_at < ?
                """, Long.class,
                tenantId, environmentId, suiteRef.suiteId(), suiteRef.revision(),
                Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
        Long expiredMatchingRuns = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ?
                  AND suite_id = ? AND suite_revision = ?
                  AND created_at >= ? AND created_at < ?
                  AND expires_at <= ?
                """, Long.class,
                tenantId, environmentId, suiteRef.suiteId(), suiteRef.revision(),
                Timestamp.from(fromInclusive), Timestamp.from(toExclusive),
                Timestamp.from(observedAt));
        if (totalMatchingRuns == null || expiredMatchingRuns == null) {
            throw new IllegalStateException("Stability history count query returned no result");
        }
        boolean truncated = totalMatchingRuns > maximumRuns;
        List<TestSuiteStabilityRunRecord> records = jdbc.query("""
                SELECT record_json
                FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ?
                  AND suite_id = ? AND suite_revision = ?
                  AND created_at >= ? AND created_at < ?
                  AND expires_at > ?
                ORDER BY created_at, stability_run_id
                FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> read(rs.getString("record_json")),
                tenantId, environmentId, suiteRef.suiteId(), suiteRef.revision(),
                Timestamp.from(fromInclusive), Timestamp.from(toExclusive),
                Timestamp.from(observedAt), maximumRuns).stream()
                .peek(value -> requireHistoryRecord(value, tenantId, environmentId, suiteRef,
                        fromInclusive, toExclusive))
                .toList();
        return new TestSuiteStabilityHistoryWindow(records,
                Math.toIntExact(expiredMatchingRuns), truncated, observedAt);
    }

    @Override
    public Optional<TestSuiteStabilityObservationLedgerHead> observationLedgerHead(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        Objects.requireNonNull(suiteRef, "suiteRef");
        String scopeFingerprint = observationScopeFingerprint(
                tenantId, environmentId, suiteRef);
        return observationHeadByScope(
                scopeFingerprint, tenantId, environmentId, suiteRef);
    }

    @Override
    public List<TestSuiteStabilityObservationLedgerEntry> observations(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long afterSequence,
            int limit) {
        Objects.requireNonNull(suiteRef, "suiteRef");
        if (afterSequence < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Stability observation ledger page is outside bounds");
        }
        String scopeFingerprint = observationScopeFingerprint(
                tenantId, environmentId, suiteRef);
        Optional<TestSuiteStabilityObservationLedgerHead> head = observationLedgerHead(
                tenantId, environmentId, suiteRef);
        if (head.isEmpty()) {
            return List.of();
        }
        TestSuiteStabilityObservationLedgerFloor floor = observationLedgerFloor(
                tenantId, environmentId, suiteRef).orElseThrow(() ->
                new IllegalStateException(
                        "Stability observation ledger has no committed floor"));
        if (afterSequence < floor.floorSequence() - 1) {
            throw new IllegalArgumentException(
                    "Stability observation cursor is before the retained floor");
        }
        if (afterSequence > head.get().latestSequence()) {
            throw new IllegalArgumentException(
                    "Stability observation cursor is beyond the committed head");
        }
        List<TestSuiteStabilityObservationLedgerEntry> entries = jdbc.query("""
                SELECT observation_id, scope_fingerprint, ledger_sequence,
                       previous_observation_id, stability_run_id, source_created_at,
                       appended_at, observation_fingerprint, attestation_fingerprint,
                       entry_fingerprint, entry_json
                FROM rg_test_suite_stability_observations
                WHERE scope_fingerprint = ? AND ledger_sequence > ?
                  AND ledger_sequence <= ?
                ORDER BY ledger_sequence
                FETCH FIRST ? ROWS ONLY
                """, this::storedObservationEntry,
                scopeFingerprint, afterSequence, head.get().latestSequence(), limit);
        long expected = afterSequence + 1;
        String predecessor = afterSequence == floor.floorSequence() - 1
                ? floor.previousObservationId() : observationIdAt(
                scopeFingerprint, afterSequence).orElseThrow(() ->
                new IllegalStateException("Stability observation cursor has no predecessor"));
        for (TestSuiteStabilityObservationLedgerEntry entry : entries) {
            requireObservationEntry(entry, scopeFingerprint, expected, predecessor);
            predecessor = entry.observation().evidence().observationId();
            expected++;
        }
        if (entries.isEmpty() && afterSequence < head.get().latestSequence()) {
            throw new IllegalStateException("Stability observation ledger has a sequence gap");
        }
        long lastSequence = expected - 1;
        if (entries.size() < limit && lastSequence < head.get().latestSequence()) {
            throw new IllegalStateException("Stability observation ledger has a tail gap");
        }
        if (lastSequence == head.get().latestSequence() && !entries.isEmpty()) {
            TestSuiteStabilityObservationLedgerEntry last = entries.getLast();
            if (!last.observation().evidence().observationId().equals(
                    head.get().latestObservationId())
                    || !last.entryFingerprint().equals(
                    head.get().latestEntryFingerprint())) {
                throw new IllegalStateException(
                        "Stability observation ledger does not close at its head");
            }
        }
        return List.copyOf(entries);
    }

    @Override
    public Optional<TestSuiteStabilityObservationLedgerRange> observationRange(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            long afterSequence,
            int limit) {
        Objects.requireNonNull(suiteRef, "suiteRef");
        if (afterSequence < 0 || limit < 1
                || limit > TestSuiteStabilityCrossRetentionTrendAnalysisRequest.MAXIMUM_RUNS) {
            throw new IllegalArgumentException(
                    "Stability observation range request is outside bounds");
        }
        String scopeFingerprint = observationScopeFingerprint(
                tenantId, environmentId, suiteRef);
        Optional<TestSuiteStabilityObservationLedgerRange> result = mutations.execute(status -> {
            lockObservationScope(scopeFingerprint);
            Optional<TestSuiteStabilityObservationLedgerHead> current =
                    observationHeadByScope(
                            scopeFingerprint, tenantId, environmentId, suiteRef);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            TestSuiteStabilityObservationLedgerHead head = current.get();
            TestSuiteStabilityObservationLedgerFloor floor = observationFloorByScope(
                    scopeFingerprint, tenantId, environmentId, suiteRef).orElseThrow(() ->
                    new IllegalStateException(
                            "Stability observation ledger has no committed floor"));
            if (afterSequence < floor.floorSequence() - 1) {
                throw new IllegalArgumentException(
                        "Stability observation cursor is before the retained floor");
            }
            if (afterSequence > head.latestSequence()) {
                throw new IllegalArgumentException(
                        "Stability observation cursor is beyond the committed head");
            }
            TestSuiteStabilityObservationLedgerEntry floorEntry = observationEntryAt(
                    scopeFingerprint, floor.floorSequence()).orElseThrow(() ->
                    new IllegalStateException(
                            "Stability observation ledger floor has no active entry"));
            requireObservationEntry(floorEntry, scopeFingerprint, floor.floorSequence(),
                    floor.previousObservationId());
            TestSuiteStabilityObservationLedgerEntry predecessor =
                    afterSequence == floor.floorSequence() - 1
                    ? null : observationEntryAt(scopeFingerprint, afterSequence)
                    .orElseThrow(() -> new IllegalStateException(
                    "Stability observation cursor has no retained entry"));
            List<TestSuiteStabilityObservationLedgerEntry> entries = observations(
                    tenantId, environmentId, suiteRef, afterSequence, limit);
            boolean hasMore = !entries.isEmpty()
                    && entries.getLast().sequence() < head.latestSequence();
            Instant observedAt = currentTime();
            TestSuiteStabilityObservationLedgerRange unsigned =
                    new TestSuiteStabilityObservationLedgerRange(
                    TestSuiteStabilityObservationLedgerRange.SCHEMA_VERSION,
                    scopeFingerprint, suiteRef, floor.floorSequence(),
                    floor.previousObservationId(), floor.previousEntryFingerprint(),
                    floorEntry.observation().evidence().observationId(),
                    floorEntry.entryFingerprint(),
                    head, afterSequence,
                    predecessor == null ? floor.previousObservationId()
                            : predecessor.observation().evidence().observationId(),
                    predecessor == null ? floor.previousEntryFingerprint()
                            : predecessor.entryFingerprint(),
                    entries, hasMore, observedAt,
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000");
            return Optional.of(new TestSuiteStabilityObservationLedgerRange(
                    unsigned.schemaVersion(), unsigned.scopeFingerprint(), unsigned.suiteRef(),
                    unsigned.floorSequence(), unsigned.floorPreviousObservationId(),
                    unsigned.floorPreviousEntryFingerprint(), unsigned.floorObservationId(),
                    unsigned.floorEntryFingerprint(), unsigned.head(), unsigned.afterSequence(),
                    unsigned.previousObservationId(), unsigned.previousEntryFingerprint(),
                    unsigned.entries(), unsigned.hasMore(), unsigned.observedAt(),
                    TestSuiteStabilityObservationLedgerRangeIntegrity.fingerprint(
                            objectMapper, unsigned)));
        });
        if (result == null) {
            throw new IllegalStateException(
                    "Stability observation range transaction returned no result");
        }
        return result;
    }

    @Override
    public Optional<TestSuiteStabilityObservationLedgerFloor> observationLedgerFloor(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        Objects.requireNonNull(suiteRef, "suiteRef");
        String scopeFingerprint = observationScopeFingerprint(
                tenantId, environmentId, suiteRef);
        return observationFloorByScope(
                scopeFingerprint, tenantId, environmentId, suiteRef);
    }

    @Override
    public Optional<TestSuiteStabilityObservationLedgerLifecyclePage>
            observationLedgerLifecyclePage(
                    String tenantId,
                    String environmentId,
                    TestSuiteStabilityObservationLedgerLifecyclePageRequest request) {
        Objects.requireNonNull(request, "request");
        String scopeFingerprint = observationScopeFingerprint(
                tenantId, environmentId, request.suiteRef());
        Optional<TestSuiteStabilityObservationLedgerLifecyclePage> result =
                mutations.execute(status -> {
                    lockObservationScope(scopeFingerprint);
                    Optional<TestSuiteStabilityObservationLedgerHead> headValue =
                            observationHeadByScope(scopeFingerprint, tenantId, environmentId,
                                    request.suiteRef());
                    if (headValue.isEmpty()) {
                        return Optional.empty();
                    }
                    TestSuiteStabilityObservationLedgerHead head = headValue.get();
                    TestSuiteStabilityObservationLedgerFloor currentFloor =
                            observationFloorByScope(scopeFingerprint, tenantId, environmentId,
                                    request.suiteRef()).orElseThrow(() ->
                                    new IllegalStateException(
                                            "Stability observation ledger has no committed floor"));
                    long currentGeneration = currentFloor.retirementGeneration();
                    if (request.afterRetirementGeneration() > currentGeneration) {
                        throw new IllegalArgumentException(
                                "Lifecycle cursor is after the current retirement generation");
                    }
                    requireCompleteObservationLifecycle(scopeFingerprint, currentFloor);

                    List<TestSuiteStabilityObservationFloorRetirement> available = jdbc.query("""
                            SELECT retirement_id, scope_fingerprint, retirement_generation,
                                   evidence_fingerprint, attestation_fingerprint,
                                   retirement_fingerprint, retired_at, retirement_json
                            FROM rg_test_suite_stability_observation_retirements
                            WHERE scope_fingerprint = ? AND retirement_generation > ?
                            ORDER BY retirement_generation
                            FETCH FIRST ? ROWS ONLY
                            """, this::storedRetirement, scopeFingerprint,
                            request.afterRetirementGeneration(),
                            request.maximumRetirements() + 1);
                    available.forEach(value ->
                            requireStoredArchive(value.evidence().archiveSegment()));
                    if (request.afterRetirementGeneration() < currentGeneration
                            && available.isEmpty()) {
                        throw new IllegalStateException(
                                "Stability observation retirement chain has a generation gap");
                    }
                    long expectedGeneration = request.afterRetirementGeneration() + 1;
                    for (TestSuiteStabilityObservationFloorRetirement retirement : available) {
                        if (retirement.evidence().retirementGeneration()
                                != expectedGeneration++) {
                            throw new IllegalStateException(
                                    "Stability observation retirement chain is not contiguous");
                        }
                    }
                    int includedCount = Math.min(
                            available.size(), request.maximumRetirements());
                    List<TestSuiteStabilityObservationFloorRetirement> retirements =
                            List.copyOf(available.subList(0, includedCount));
                    TestSuiteStabilityObservationLedgerFloor startingFloor =
                            retirements.isEmpty() ? currentFloor
                                    : retirements.getFirst().evidence().previousFloor();
                    if (startingFloor.retirementGeneration()
                            != request.afterRetirementGeneration()) {
                        throw new IllegalStateException(
                                "Lifecycle cursor does not resolve to an exact starting floor");
                    }
                    if (request.afterRetirementGeneration() > 0) {
                        List<TestSuiteStabilityObservationFloorRetirement> predecessor = jdbc.query(
                                """
                                SELECT retirement_id, scope_fingerprint, retirement_generation,
                                       evidence_fingerprint, attestation_fingerprint,
                                       retirement_fingerprint, retired_at, retirement_json
                                FROM rg_test_suite_stability_observation_retirements
                                WHERE scope_fingerprint = ? AND retirement_generation = ?
                                """, this::storedRetirement, scopeFingerprint,
                                request.afterRetirementGeneration());
                        if (predecessor.size() != 1) {
                            throw new IllegalStateException(
                                    "Lifecycle cursor predecessor is not unique");
                        }
                        requireStoredArchive(
                                predecessor.getFirst().evidence().archiveSegment());
                        if (!TestSuiteStabilityObservationFloorRetirementIntegrity
                                .successorFloor(objectMapper, predecessor.getFirst())
                                .equals(startingFloor)) {
                            throw new IllegalStateException(
                                    "Lifecycle cursor predecessor does not close on its floor");
                        }
                    }
                    TestSuiteStabilityObservationLedgerFloor terminalFloor = startingFloor;
                    for (TestSuiteStabilityObservationFloorRetirement retirement : retirements) {
                        if (!retirement.evidence().previousFloor().equals(terminalFloor)) {
                            throw new IllegalStateException(
                                    "Stability observation retirement floor chain is broken");
                        }
                        terminalFloor = TestSuiteStabilityObservationFloorRetirementIntegrity
                                .successorFloor(objectMapper, retirement);
                    }
                    boolean hasMore = terminalFloor.retirementGeneration() < currentGeneration;
                    if ((hasMore && available.size() <= request.maximumRetirements())
                            || (!hasMore && available.size() > request.maximumRetirements())
                            || (!hasMore && !terminalFloor.equals(currentFloor))) {
                        throw new IllegalStateException(
                                "Stability observation lifecycle page contradicts its current floor");
                    }
                    Instant observedAt = currentTime();
                    String requestFingerprint = ProtocolFingerprint.of(objectMapper, request);
                    TestSuiteStabilityObservationLedgerLifecyclePage unsigned =
                            new TestSuiteStabilityObservationLedgerLifecyclePage(
                                    TestSuiteStabilityObservationLedgerLifecyclePage.SCHEMA_VERSION,
                                    requestFingerprint, request, scopeFingerprint, startingFloor,
                                    retirements, terminalFloor, currentFloor, head, hasMore,
                                    observedAt, zeroFingerprint());
                    return Optional.of(
                            new TestSuiteStabilityObservationLedgerLifecyclePage(
                                    unsigned.schemaVersion(), unsigned.requestFingerprint(),
                                    unsigned.request(), unsigned.scopeFingerprint(),
                                    unsigned.startingFloor(), unsigned.retirements(),
                                    unsigned.terminalFloor(), unsigned.currentFloor(),
                                    unsigned.head(), unsigned.hasMore(), unsigned.observedAt(),
                                    TestSuiteStabilityObservationLedgerLifecyclePageIntegrity
                                            .pageFingerprint(objectMapper, unsigned)));
                });
        if (result == null) {
            throw new IllegalStateException(
                    "Stability observation lifecycle transaction returned no result");
        }
        return result;
    }

    @Override
    public Optional<TestSuiteStabilityObservationFloorRetirementEvidence>
            planObservationFloorRetirement(
                    String tenantId,
                    String environmentId,
                    TestSuiteExecutionRequest.SuiteRef suiteRef,
                    Instant cutoffExclusive,
                    int minimumRetainedEntries,
                    int maximumRetiredEntries,
                    String retentionPolicyFingerprint) {
        Objects.requireNonNull(suiteRef, "suiteRef");
        Objects.requireNonNull(cutoffExclusive, "cutoffExclusive");
        String policyFingerprint = normalized(retentionPolicyFingerprint);
        if (minimumRetainedEntries < 1
                || minimumRetainedEntries
                > TestSuiteStabilityObservationFloorRetirementEvidence
                .MAXIMUM_RETAINED_ENTRIES
                || maximumRetiredEntries < 1
                || maximumRetiredEntries
                > TestSuiteStabilityObservationArchiveSegment.MAXIMUM_ENTRIES
                || !FINGERPRINT.matcher(policyFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Stability observation retirement policy is outside bounds");
        }
        String scopeFingerprint = observationScopeFingerprint(
                tenantId, environmentId, suiteRef);
        Optional<TestSuiteStabilityObservationFloorRetirementEvidence> result =
                mutations.execute(status -> {
                    lockObservationScope(scopeFingerprint);
                    Instant retiredAt = currentTime();
                    if (cutoffExclusive.isAfter(retiredAt)) {
                        throw new IllegalArgumentException(
                                "Stability observation retirement cutoff is in the future");
                    }
                    Optional<TestSuiteStabilityObservationLedgerHead> headValue =
                            observationHeadByScope(
                                    scopeFingerprint, tenantId, environmentId, suiteRef);
                    if (headValue.isEmpty()) {
                        return Optional.empty();
                    }
                    TestSuiteStabilityObservationLedgerHead head = headValue.get();
                    TestSuiteStabilityObservationLedgerFloor floor = observationFloorByScope(
                            scopeFingerprint, tenantId, environmentId, suiteRef).orElseThrow(() ->
                            new IllegalStateException(
                                    "Stability observation ledger has no committed floor"));
                    long maximumThrough = head.latestSequence() - minimumRetainedEntries;
                    if (maximumThrough < floor.floorSequence()) {
                        return Optional.empty();
                    }
                    List<TestSuiteStabilityObservationLedgerEntry> candidates = jdbc.query("""
                            SELECT observation_id, scope_fingerprint, ledger_sequence,
                                   previous_observation_id, stability_run_id, source_created_at,
                                   appended_at, observation_fingerprint, attestation_fingerprint,
                                   entry_fingerprint, entry_json
                            FROM rg_test_suite_stability_observations
                            WHERE scope_fingerprint = ? AND ledger_sequence >= ?
                              AND ledger_sequence <= ?
                            ORDER BY ledger_sequence
                            FETCH FIRST ? ROWS ONLY
                            """, this::storedObservationEntry, scopeFingerprint,
                            floor.floorSequence(), maximumThrough, maximumRetiredEntries);
                    int eligible = 0;
                    String predecessor = floor.previousObservationId();
                    long sequence = floor.floorSequence();
                    for (TestSuiteStabilityObservationLedgerEntry candidate : candidates) {
                        requireObservationEntry(
                                candidate, scopeFingerprint, sequence, predecessor);
                        if (!candidate.appendedAt().isBefore(cutoffExclusive)) {
                            break;
                        }
                        eligible++;
                        predecessor = candidate.observation().evidence().observationId();
                        sequence++;
                    }
                    if (eligible == 0) {
                        return Optional.empty();
                    }
                    List<TestSuiteStabilityObservationLedgerEntry> retired = List.copyOf(
                            candidates.subList(0, eligible));
                    TestSuiteStabilityObservationLedgerEntry successor = observationEntryAt(
                            scopeFingerprint, retired.getLast().sequence() + 1).orElseThrow(() ->
                            new IllegalStateException(
                                    "Stability observation retirement has no surviving successor"));
                    requireObservationEntry(successor, scopeFingerprint,
                            retired.getLast().sequence() + 1,
                            retired.getLast().observation().evidence().observationId());
                    long generation = floor.retirementGeneration() + 1;
                    String archiveId =
                            TestSuiteStabilityObservationFloorRetirementIntegrity.archiveId(
                                    objectMapper, scopeFingerprint, suiteRef, generation,
                                    floor.previousObservationId(),
                                    floor.previousEntryFingerprint(), retired, successor,
                                    retiredAt);
                    TestSuiteStabilityObservationArchiveSegment unsignedArchive =
                            new TestSuiteStabilityObservationArchiveSegment(
                                    TestSuiteStabilityObservationArchiveSegment.SCHEMA_VERSION,
                                    archiveId, scopeFingerprint, suiteRef, generation,
                                    floor.previousObservationId(),
                                    floor.previousEntryFingerprint(), retired, successor,
                                    retiredAt, zeroFingerprint());
                    TestSuiteStabilityObservationArchiveSegment archive =
                            new TestSuiteStabilityObservationArchiveSegment(
                                    unsignedArchive.schemaVersion(), unsignedArchive.segmentId(),
                                    unsignedArchive.scopeFingerprint(), unsignedArchive.suiteRef(),
                                    unsignedArchive.retirementGeneration(),
                                    unsignedArchive.previousObservationId(),
                                    unsignedArchive.previousEntryFingerprint(),
                                    unsignedArchive.retiredEntries(),
                                    unsignedArchive.successorEntry(), unsignedArchive.archivedAt(),
                                    TestSuiteStabilityObservationFloorRetirementIntegrity
                                            .archiveFingerprint(objectMapper, unsignedArchive));
                    TestSuiteStabilityObservationFloorRetirementEvidence unsignedEvidence =
                            new TestSuiteStabilityObservationFloorRetirementEvidence(
                                    TestSuiteStabilityObservationFloorRetirementEvidence
                                            .SCHEMA_VERSION,
                                    zeroRetirementId(), scopeFingerprint, suiteRef, generation,
                                    floor, head, archive, cutoffExclusive, minimumRetainedEntries,
                                    maximumRetiredEntries, policyFingerprint,
                                    TestSuiteStabilityObservationFloorRetirementEvidence.Reason
                                            .RETENTION_POLICY,
                                    retiredAt);
                    String retirementId =
                            TestSuiteStabilityObservationFloorRetirementIntegrity.retirementId(
                                    objectMapper, unsignedEvidence);
                    return Optional.of(
                            new TestSuiteStabilityObservationFloorRetirementEvidence(
                                    unsignedEvidence.schemaVersion(), retirementId,
                                    unsignedEvidence.scopeFingerprint(), unsignedEvidence.suiteRef(),
                                    unsignedEvidence.retirementGeneration(),
                                    unsignedEvidence.previousFloor(), unsignedEvidence.pinnedHead(),
                                    unsignedEvidence.archiveSegment(),
                                    unsignedEvidence.cutoffExclusive(),
                                    unsignedEvidence.minimumRetainedEntries(),
                                    unsignedEvidence.maximumRetiredEntries(),
                                    unsignedEvidence.retentionPolicyFingerprint(),
                                    unsignedEvidence.reason(), unsignedEvidence.retiredAt()));
                });
        if (result == null) {
            throw new IllegalStateException(
                    "Stability observation retirement planning transaction returned no result");
        }
        return result;
    }

    @Override
    public TestSuiteStabilityObservationLedgerFloor commitObservationFloorRetirement(
            TestSuiteStabilityObservationFloorRetirement retirement,
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
        Objects.requireNonNull(retirement, "retirement");
        Objects.requireNonNull(receiptSet, "receiptSet");
        if (!TestSuiteStabilityObservationFloorRetirementIntegrity.valid(
                objectMapper, retirement)) {
            throw new IllegalArgumentException(
                    "Canonical signed stability observation floor retirement is required");
        }
        if (!TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                objectMapper, receiptSet)
                || !retirement.equals(receiptSet.request().retirement())) {
            throw new IllegalArgumentException(
                    "Canonical external archive receipts for the exact retirement are required");
        }
        TestSuiteStabilityObservationFloorRetirementEvidence evidence = retirement.evidence();
        TestSuiteStabilityObservationArchiveSegment archive = evidence.archiveSegment();
        TestSuiteStabilityObservationLedgerFloor result = mutations.execute(status -> {
            lockObservationScope(evidence.scopeFingerprint());
            Optional<TestSuiteStabilityObservationFloorRetirement> replay =
                    findObservationFloorRetirement(evidence.retirementId());
            if (replay.isPresent()) {
                if (!replay.get().equals(retirement)) {
                    throw new IllegalStateException(
                            "Stability observation retirement identity has different material");
                }
                TestSuiteStabilityObservationExternalArchiveReceiptSet storedReceiptSet =
                        findObservationExternalArchiveReceiptSet(evidence.retirementId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Committed retirement has no external archive receipts"));
                if (!storedReceiptSet.equals(receiptSet)) {
                    throw new IllegalStateException(
                            "Stability observation retirement replay has different archive receipts");
                }
                return successorFloor(retirement);
            }
            TestSuiteStabilityObservationLedgerFloor currentFloor = observationFloorByScope(
                    evidence.scopeFingerprint(), "", "", evidence.suiteRef(), false)
                    .orElseThrow(() -> new IllegalStateException(
                            "Stability observation ledger has no committed floor"));
            TestSuiteStabilityObservationLedgerHead currentHead = observationHeadByScope(
                    evidence.scopeFingerprint(), "", "", evidence.suiteRef(), false)
                    .orElseThrow(() -> new IllegalStateException(
                            "Stability observation ledger has no committed head"));
            if (!currentFloor.equals(evidence.previousFloor())
                    || !currentHead.equals(evidence.pinnedHead())) {
                throw new IllegalStateException(
                        "Stability observation retirement floor or head pin changed");
            }
            requireArchiveMatchesActiveRows(archive);
            if (currentTime().isBefore(evidence.retiredAt())) {
                throw new IllegalStateException(
                        "Stability observation retirement is ahead of database time");
            }
            insertExternalArchiveReceiptSet(receiptSet);
            insertArchive(archive);
            insertRetirement(retirement);
            TestSuiteStabilityObservationLedgerFloor successorFloor =
                    successorFloor(retirement);
            TestSuiteStabilityObservationLedgerHead unsignedHead =
                    new TestSuiteStabilityObservationLedgerHead(
                            currentHead.schemaVersion(), currentHead.scopeFingerprint(),
                            currentHead.suiteRef(), successorFloor.coverageFrom(),
                            currentHead.latestSequence(), currentHead.latestObservationId(),
                            currentHead.latestEntryFingerprint(), currentHead.updatedAt(),
                            zeroFingerprint());
            TestSuiteStabilityObservationLedgerHead successorHead =
                    new TestSuiteStabilityObservationLedgerHead(
                            unsignedHead.schemaVersion(), unsignedHead.scopeFingerprint(),
                            unsignedHead.suiteRef(), unsignedHead.coverageFrom(),
                            unsignedHead.latestSequence(), unsignedHead.latestObservationId(),
                            unsignedHead.latestEntryFingerprint(), unsignedHead.updatedAt(),
                            TestSuiteStabilityObservationLedgerHeadIntegrity.fingerprint(
                                    objectMapper, unsignedHead));
            advanceFloor(currentFloor, successorFloor);
            advanceHeadForRetirement(currentHead, successorHead);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observations
                    WHERE scope_fingerprint = ? AND ledger_sequence >= ?
                      AND ledger_sequence <= ?
                    """, evidence.scopeFingerprint(), archive.fromSequence(),
                    archive.throughSequence());
            if (deleted != archive.retiredEntries().size()) {
                throw new IllegalStateException(
                        "Stability observation active prefix deletion was incomplete");
            }
            return successorFloor;
        });
        if (result == null) {
            throw new IllegalStateException(
                    "Stability observation retirement commit returned no successor floor");
        }
        return result;
    }

    @Override
    public Optional<TestSuiteStabilityObservationFloorRetirement>
            findObservationFloorRetirement(String retirementId) {
        String exactId = normalized(retirementId);
        if (!exactId.matches("stability-observation-retirement-[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Complete stability observation retirement id is required");
        }
        List<TestSuiteStabilityObservationFloorRetirement> values = jdbc.query("""
                SELECT retirement_id, scope_fingerprint, retirement_generation,
                       evidence_fingerprint, attestation_fingerprint,
                       retirement_fingerprint, retired_at, retirement_json
                FROM rg_test_suite_stability_observation_retirements
                WHERE retirement_id = ?
                """, this::storedRetirement, exactId);
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Stability observation retirement identity is not unique");
        }
        Optional<TestSuiteStabilityObservationFloorRetirement> result =
                values.stream().findFirst();
        result.ifPresent(value -> requireStoredArchive(value.evidence().archiveSegment()));
        return result;
    }

    @Override
    public Optional<TestSuiteStabilityObservationExternalArchiveReceiptSet>
            findObservationExternalArchiveReceiptSet(String retirementId) {
        String exactId = normalized(retirementId);
        if (!exactId.matches("stability-observation-retirement-[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Complete stability observation retirement id is required");
        }
        List<TestSuiteStabilityObservationExternalArchiveReceiptSet> values = jdbc.query("""
                SELECT receipt_set_id, retirement_id, scope_fingerprint,
                       retirement_generation, segment_id, retirement_fingerprint,
                       segment_fingerprint, retention_policy_fingerprint, retain_until,
                       required_copies, receipt_count, confirmed_at,
                       receipt_set_fingerprint, receipt_set_json
                FROM rg_test_suite_stability_observation_archive_receipts
                WHERE retirement_id = ?
                """, this::storedExternalArchiveReceiptSet, exactId);
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "External observation archive receipt identity is not unique");
        }
        return values.stream().findFirst();
    }

    private Optional<TestSuiteStabilityRunRecord> query(String sql, Object... arguments) {
        List<TestSuiteStabilityRunRecord> records = jdbc.query(sql,
                (rs, row) -> read(rs.getString("record_json")), arguments);
        return records.stream().findFirst();
    }

    private static void requireHistoryRecord(
            TestSuiteStabilityRunRecord record,
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            Instant fromInclusive,
            Instant toExclusive) {
        if (!tenantId.equals(record.tenantId())
                || !environmentId.equals(record.environmentId())
                || !suiteRef.equals(record.evidence().suiteRef())
                || record.createdAt().isBefore(fromInclusive)
                || !record.createdAt().isBefore(toExclusive)) {
            throw new IllegalStateException(
                    "Stored stability history row contradicts its indexed projection");
        }
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

    private Optional<TestSuiteStabilityRunRecord> terminalByRunId(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        return query("""
                SELECT record_json FROM rg_test_suite_stability_records
                WHERE tenant_id = ? AND environment_id = ? AND stability_run_id = ?
                """, tenantId, environmentId, stabilityRunId);
    }

    private Optional<TestSuiteStabilityExecutionStop> stopByClientRequestId(
            String tenantId,
            String environmentId,
            String clientRequestId) {
        List<TestSuiteStabilityExecutionStop> stops = jdbc.query("""
                SELECT * FROM rg_test_suite_stability_execution_stops
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, this::storedStop,
                tenantId, environmentId, clientRequestId);
        return stops.stream().findFirst();
    }

    private Optional<TestSuiteStabilityExecutionStop> stopByRunId(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        List<TestSuiteStabilityExecutionStop> stops = jdbc.query("""
                SELECT * FROM rg_test_suite_stability_execution_stops
                WHERE tenant_id = ? AND environment_id = ? AND stability_run_id = ?
                """, this::storedStop, tenantId, environmentId, stabilityRunId);
        return stops.stream().findFirst();
    }

    private TestSuiteStabilityExecutionStop executionStop(
            TestSuiteStabilityExecutionStopRequest request,
            Instant createdAt) {
        Instant expiresAt = createdAt.plus(request.retention());
        String fingerprint = stopFingerprint(request.stabilityRunId(), request.tenantId(),
                request.environmentId(), request.clientRequestId(), request.requestFingerprint(),
                request.classification(), request.reason(), request.failureCode(),
                request.actorId(), createdAt, expiresAt);
        return new TestSuiteStabilityExecutionStop(
                request.stabilityRunId(), request.tenantId(), request.environmentId(),
                request.clientRequestId(), request.requestFingerprint(), request.classification(),
                request.reason(), request.failureCode(), request.actorId(), createdAt, expiresAt,
                fingerprint);
    }

    private TestSuiteStabilityExecutionProgress createProgress(
            TestSuiteStabilityLeaseRequest request,
            Instant observedAt) {
        TestSuiteStabilityExecutionProgress progress =
                new TestSuiteStabilityExecutionProgress(
                        request.stabilityRunId(), request.tenantId(), request.environmentId(),
                        request.clientRequestId(), request.requestFingerprint(), request.suiteRef(),
                        request.classification(), request.plannedAttempts(), List.of(),
                        observedAt, observedAt,
                        observedAt.plus(request.progressRetention()));
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_progress (
                        stability_run_id, tenant_id, environment_id, client_request_id,
                        request_fingerprint, suite_id, suite_revision, suite_fingerprint,
                        classification, planned_attempts, completed_attempts, attempts_json,
                        created_at, updated_at, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, progress.stabilityRunId(), progress.tenantId(), progress.environmentId(),
                    progress.clientRequestId(), progress.requestFingerprint(),
                    progress.suiteRef().suiteId(), progress.suiteRef().revision(),
                    progress.suiteRef().fingerprint(), progress.classification(),
                    progress.plannedAttempts(),
                    progress.completedAttempts(), writeAttempts(progress.attempts()),
                    Timestamp.from(progress.createdAt()), Timestamp.from(progress.updatedAt()),
                    Timestamp.from(progress.expiresAt()));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Stability progress insert did not create exactly one row");
            }
        } catch (DuplicateKeyException collision) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                    "Deterministic stability progress identity already belongs to another row");
        }
        return progress;
    }

    private Optional<TestSuiteStabilityExecutionProgress> progressByClientRequestId(
            String tenantId,
            String environmentId,
            String clientRequestId) {
        return progress("""
                SELECT stability_run_id, tenant_id, environment_id, client_request_id,
                       request_fingerprint, suite_id, suite_revision, suite_fingerprint,
                       classification, planned_attempts, completed_attempts, attempts_json,
                       created_at, updated_at, expires_at
                FROM rg_test_suite_stability_progress
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, tenantId, environmentId, clientRequestId);
    }

    private Optional<TestSuiteStabilityExecutionProgress> progressByRunId(
            String tenantId,
            String environmentId,
            String stabilityRunId) {
        return progress("""
                SELECT stability_run_id, tenant_id, environment_id, client_request_id,
                       request_fingerprint, suite_id, suite_revision, suite_fingerprint,
                       classification, planned_attempts, completed_attempts, attempts_json,
                       created_at, updated_at, expires_at
                FROM rg_test_suite_stability_progress
                WHERE tenant_id = ? AND environment_id = ? AND stability_run_id = ?
                """, tenantId, environmentId, stabilityRunId);
    }

    private Optional<TestSuiteStabilityExecutionProgress> progress(
            String sql,
            Object... arguments) {
        List<TestSuiteStabilityExecutionProgress> records = jdbc.query(sql, (rs, row) -> {
            List<TestSuiteStabilityExecutionProgress.AttemptReference> attempts =
                    readAttempts(rs.getString("attempts_json"));
            int completed = rs.getInt("completed_attempts");
            if (completed != attempts.size()) {
                throw new IllegalStateException(
                        "Stored stability progress count contradicts its attempt journal");
            }
            return new TestSuiteStabilityExecutionProgress(
                    rs.getString("stability_run_id"), rs.getString("tenant_id"),
                    rs.getString("environment_id"), rs.getString("client_request_id"),
                    rs.getString("request_fingerprint"),
                    new com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest.SuiteRef(
                            rs.getString("suite_id"), rs.getLong("suite_revision"),
                            rs.getString("suite_fingerprint")),
                    rs.getString("classification"), rs.getInt("planned_attempts"), attempts,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant());
        }, arguments);
        return records.stream().findFirst();
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

    private void appendObservation(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityObservation observation,
            Instant appendedAt) {
        String scopeFingerprint = observationScopeFingerprint(
                record.tenantId(), record.environmentId(), record.evidence().suiteRef());
        Optional<TestSuiteStabilityObservationLedgerHead> current =
                observationHeadByScope(scopeFingerprint, record.tenantId(),
                        record.environmentId(), record.evidence().suiteRef());
        long sequence = current.map(TestSuiteStabilityObservationLedgerHead::latestSequence)
                .orElse(0L) + 1L;
        String predecessor = current.map(
                TestSuiteStabilityObservationLedgerHead::latestObservationId).orElse("");
        TestSuiteStabilityObservationLedgerEntry unsignedEntry =
                new TestSuiteStabilityObservationLedgerEntry(
                        TestSuiteStabilityObservationLedgerEntry.SCHEMA_VERSION,
                        scopeFingerprint, sequence, predecessor, observation,
                        appendedAt,
                        "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        String entryFingerprint = TestSuiteStabilityObservationLedgerEntryIntegrity.fingerprint(
                objectMapper, unsignedEntry);
        TestSuiteStabilityObservationLedgerEntry entry =
                new TestSuiteStabilityObservationLedgerEntry(
                        unsignedEntry.schemaVersion(), unsignedEntry.scopeFingerprint(),
                        unsignedEntry.sequence(), unsignedEntry.previousObservationId(),
                        unsignedEntry.observation(), unsignedEntry.appendedAt(),
                        entryFingerprint);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_observations (
                        observation_id, scope_fingerprint, ledger_sequence,
                        previous_observation_id, stability_run_id, source_created_at,
                        appended_at, observation_fingerprint, attestation_fingerprint,
                        entry_fingerprint, entry_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, observation.evidence().observationId(), scopeFingerprint,
                    sequence, predecessor, record.stabilityRunId(),
                    Timestamp.from(record.createdAt()), Timestamp.from(appendedAt),
                    observation.evidenceFingerprint(), observation.attestationFingerprint(),
                    entryFingerprint, writeObservationEntry(entry));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Stability observation append did not create exactly one row");
            }
        } catch (DuplicateKeyException duplicate) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                    "Stability observation identity or sequence already exists");
        }

        Instant coverageFrom = current.map(
                TestSuiteStabilityObservationLedgerHead::coverageFrom).orElse(appendedAt);
        TestSuiteStabilityObservationLedgerHead unsignedHead =
                new TestSuiteStabilityObservationLedgerHead(
                        TestSuiteStabilityObservationLedgerHead.SCHEMA_VERSION,
                        scopeFingerprint, record.evidence().suiteRef(), coverageFrom,
                        sequence, observation.evidence().observationId(), entryFingerprint,
                        appendedAt,
                        "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        TestSuiteStabilityObservationLedgerHead successor =
                new TestSuiteStabilityObservationLedgerHead(
                        unsignedHead.schemaVersion(), unsignedHead.scopeFingerprint(),
                        unsignedHead.suiteRef(), unsignedHead.coverageFrom(),
                        unsignedHead.latestSequence(), unsignedHead.latestObservationId(),
                        unsignedHead.latestEntryFingerprint(), unsignedHead.updatedAt(),
                        TestSuiteStabilityObservationLedgerHeadIntegrity.fingerprint(
                                objectMapper, unsignedHead));
        if (current.isEmpty()) {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_observation_heads (
                        scope_fingerprint, tenant_id, environment_id, suite_id,
                        suite_revision, suite_fingerprint, coverage_from, latest_sequence,
                        latest_observation_id, latest_entry_fingerprint, updated_at,
                        head_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, scopeFingerprint, record.tenantId(), record.environmentId(),
                    record.evidence().suiteRef().suiteId(),
                    record.evidence().suiteRef().revision(),
                    record.evidence().suiteRef().fingerprint(),
                    Timestamp.from(successor.coverageFrom()), successor.latestSequence(),
                    successor.latestObservationId(), successor.latestEntryFingerprint(),
                    Timestamp.from(successor.updatedAt()), successor.headFingerprint());
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Stability observation rollout floor was not created");
            }
            insertRolloutFloor(record.tenantId(), record.environmentId(), entry);
        } else {
            TestSuiteStabilityObservationLedgerHead predecessorHead = current.get();
            int updated = jdbc.update("""
                    UPDATE rg_test_suite_stability_observation_heads
                    SET latest_sequence = ?, latest_observation_id = ?,
                        latest_entry_fingerprint = ?, updated_at = ?, head_fingerprint = ?
                    WHERE scope_fingerprint = ? AND latest_sequence = ?
                      AND latest_observation_id = ? AND latest_entry_fingerprint = ?
                      AND head_fingerprint = ?
                    """, successor.latestSequence(), successor.latestObservationId(),
                    successor.latestEntryFingerprint(), Timestamp.from(successor.updatedAt()),
                    successor.headFingerprint(), scopeFingerprint,
                    predecessorHead.latestSequence(), predecessorHead.latestObservationId(),
                    predecessorHead.latestEntryFingerprint(),
                    predecessorHead.headFingerprint());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Stability observation head did not advance from its exact predecessor");
            }
        }
    }

    private void insertRolloutFloor(
            String tenantId,
            String environmentId,
            TestSuiteStabilityObservationLedgerEntry first) {
        TestSuiteStabilityObservationLedgerFloor unsigned =
                new TestSuiteStabilityObservationLedgerFloor(
                        TestSuiteStabilityObservationLedgerFloor.SCHEMA_VERSION,
                        first.scopeFingerprint(), first.observation().evidence().suiteRef(),
                        1L, "", "", first.observation().evidence().observationId(),
                        first.entryFingerprint(), first.appendedAt(), 0L, "", "",
                        first.appendedAt(), zeroFingerprint());
        insertFloorRow(tenantId, environmentId, copyFloorWithFingerprint(
                unsigned, TestSuiteStabilityObservationLedgerFloorIntegrity.fingerprint(
                        objectMapper, unsigned)));
    }

    private void insertFloorRow(
            String tenantId,
            String environmentId,
            TestSuiteStabilityObservationLedgerFloor floor) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_suite_stability_observation_floors (
                    scope_fingerprint, tenant_id, environment_id, suite_id, suite_revision,
                    suite_fingerprint, floor_sequence, previous_observation_id,
                    previous_entry_fingerprint, floor_observation_id,
                    floor_entry_fingerprint, coverage_from, retirement_generation,
                    latest_retirement_id, latest_retirement_fingerprint, updated_at,
                    floor_fingerprint, floor_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, floor.scopeFingerprint(), tenantId, environmentId,
                floor.suiteRef().suiteId(), floor.suiteRef().revision(),
                floor.suiteRef().fingerprint(), floor.floorSequence(),
                floor.previousObservationId(), floor.previousEntryFingerprint(),
                floor.floorObservationId(), floor.floorEntryFingerprint(),
                Timestamp.from(floor.coverageFrom()), floor.retirementGeneration(),
                floor.latestRetirementId(), floor.latestRetirementFingerprint(),
                Timestamp.from(floor.updatedAt()), floor.floorFingerprint(), writeFloor(floor));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Stability observation rollout floor insert was incomplete");
        }
    }

    private void advanceFloor(
            TestSuiteStabilityObservationLedgerFloor predecessor,
            TestSuiteStabilityObservationLedgerFloor successor) {
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_floors
                SET floor_sequence = ?, previous_observation_id = ?,
                    previous_entry_fingerprint = ?, floor_observation_id = ?,
                    floor_entry_fingerprint = ?, coverage_from = ?,
                    retirement_generation = ?, latest_retirement_id = ?,
                    latest_retirement_fingerprint = ?, updated_at = ?,
                    floor_fingerprint = ?, floor_json = ?
                WHERE scope_fingerprint = ? AND floor_sequence = ?
                  AND retirement_generation = ? AND floor_fingerprint = ?
                """, successor.floorSequence(), successor.previousObservationId(),
                successor.previousEntryFingerprint(), successor.floorObservationId(),
                successor.floorEntryFingerprint(), Timestamp.from(successor.coverageFrom()),
                successor.retirementGeneration(), successor.latestRetirementId(),
                successor.latestRetirementFingerprint(), Timestamp.from(successor.updatedAt()),
                successor.floorFingerprint(), writeFloor(successor),
                predecessor.scopeFingerprint(), predecessor.floorSequence(),
                predecessor.retirementGeneration(), predecessor.floorFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Stability observation floor did not advance from its exact predecessor");
        }
    }

    private void advanceHeadForRetirement(
            TestSuiteStabilityObservationLedgerHead predecessor,
            TestSuiteStabilityObservationLedgerHead successor) {
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_heads
                SET coverage_from = ?, head_fingerprint = ?
                WHERE scope_fingerprint = ? AND latest_sequence = ?
                  AND latest_observation_id = ? AND latest_entry_fingerprint = ?
                  AND updated_at = ? AND head_fingerprint = ?
                """, Timestamp.from(successor.coverageFrom()), successor.headFingerprint(),
                predecessor.scopeFingerprint(), predecessor.latestSequence(),
                predecessor.latestObservationId(), predecessor.latestEntryFingerprint(),
                Timestamp.from(predecessor.updatedAt()), predecessor.headFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Stability observation head did not accept the retired floor");
        }
    }

    private void requireArchiveMatchesActiveRows(
            TestSuiteStabilityObservationArchiveSegment archive) {
        String predecessor = archive.previousObservationId();
        long sequence = archive.fromSequence();
        for (TestSuiteStabilityObservationLedgerEntry expected : archive.retiredEntries()) {
            TestSuiteStabilityObservationLedgerEntry stored = observationEntryAt(
                    archive.scopeFingerprint(), sequence).orElseThrow(() ->
                    new IllegalStateException(
                            "Stability observation retirement active prefix is incomplete"));
            requireObservationEntry(stored, archive.scopeFingerprint(), sequence, predecessor);
            if (!stored.equals(expected)) {
                throw new IllegalStateException(
                        "Stability observation retirement active prefix changed");
            }
            predecessor = stored.observation().evidence().observationId();
            sequence++;
        }
        TestSuiteStabilityObservationLedgerEntry successor = observationEntryAt(
                archive.scopeFingerprint(), sequence).orElseThrow(() ->
                new IllegalStateException(
                        "Stability observation retirement successor is unavailable"));
        requireObservationEntry(successor, archive.scopeFingerprint(), sequence, predecessor);
        if (!successor.equals(archive.successorEntry())) {
            throw new IllegalStateException(
                    "Stability observation retirement successor changed");
        }
    }

    private void insertArchive(TestSuiteStabilityObservationArchiveSegment archive) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_observation_archives (
                        segment_id, scope_fingerprint, retirement_generation,
                        from_sequence, through_sequence, segment_fingerprint,
                        archived_at, segment_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, archive.segmentId(), archive.scopeFingerprint(),
                    archive.retirementGeneration(), archive.fromSequence(),
                    archive.throughSequence(), archive.segmentFingerprint(),
                    Timestamp.from(archive.archivedAt()), writeArchive(archive));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Stability observation archive insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "Stability observation archive generation already exists", duplicate);
        }
    }

    private void insertExternalArchiveReceiptSet(
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
        TestSuiteStabilityObservationFloorRetirement retirement =
                receiptSet.request().retirement();
        TestSuiteStabilityObservationFloorRetirementEvidence evidence = retirement.evidence();
        TestSuiteStabilityObservationArchiveSegment archive = evidence.archiveSegment();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_observation_archive_receipts (
                        receipt_set_id, retirement_id, scope_fingerprint,
                        retirement_generation, segment_id, retirement_fingerprint,
                        segment_fingerprint, retention_policy_fingerprint, retain_until,
                        required_copies, receipt_count, confirmed_at,
                        receipt_set_fingerprint, receipt_set_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, receiptSet.receiptSetId(), evidence.retirementId(),
                    evidence.scopeFingerprint(), evidence.retirementGeneration(),
                    archive.segmentId(), retirement.retirementFingerprint(),
                    archive.segmentFingerprint(), evidence.retentionPolicyFingerprint(),
                    Timestamp.from(receiptSet.request().retainUntil()),
                    receiptSet.requiredCopies(), receiptSet.receipts().size(),
                    Timestamp.from(receiptSet.confirmedAt()),
                    receiptSet.receiptSetFingerprint(),
                    writeExternalArchiveReceiptSet(receiptSet));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "External observation archive receipt insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External observation archive receipt generation already exists", duplicate);
        }
    }

    private void insertRetirement(
            TestSuiteStabilityObservationFloorRetirement retirement) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_observation_retirements (
                        retirement_id, scope_fingerprint, retirement_generation,
                        evidence_fingerprint, attestation_fingerprint,
                        retirement_fingerprint, retired_at, retirement_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, retirement.evidence().retirementId(),
                    retirement.evidence().scopeFingerprint(),
                    retirement.evidence().retirementGeneration(),
                    retirement.evidenceFingerprint(), retirement.attestationFingerprint(),
                    retirement.retirementFingerprint(),
                    Timestamp.from(retirement.evidence().retiredAt()),
                    writeRetirement(retirement));
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Stability observation retirement insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "Stability observation retirement generation already exists", duplicate);
        }
    }

    private static TestSuiteStabilityObservationLedgerFloor copyFloorWithFingerprint(
            TestSuiteStabilityObservationLedgerFloor floor,
            String fingerprint) {
        return new TestSuiteStabilityObservationLedgerFloor(
                floor.schemaVersion(), floor.scopeFingerprint(), floor.suiteRef(),
                floor.floorSequence(), floor.previousObservationId(),
                floor.previousEntryFingerprint(), floor.floorObservationId(),
                floor.floorEntryFingerprint(), floor.coverageFrom(),
                floor.retirementGeneration(), floor.latestRetirementId(),
                floor.latestRetirementFingerprint(), floor.updatedAt(), fingerprint);
    }

    private TestSuiteStabilityObservationLedgerFloor successorFloor(
            TestSuiteStabilityObservationFloorRetirement retirement) {
        return TestSuiteStabilityObservationFloorRetirementIntegrity.successorFloor(
                objectMapper, retirement);
    }

    private void requireObservation(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityObservation observation) {
        if (observation == null || observation.evidence() == null
                || observation.attestation() == null
                || !observation.evidenceFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, observation.evidence()))
                || !observation.attestationFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, observation.attestation()))
                || !observation.evidence().scopeFingerprint().equals(
                observationScopeFingerprint(record.tenantId(), record.environmentId(),
                        record.evidence().suiteRef()))
                || !observation.evidence().suiteRef().equals(record.evidence().suiteRef())
                || !observation.evidence().sourceRequestFingerprint().equals(
                record.requestFingerprint())
                || !observation.evidence().source().equals(
                observationProjector.project(record))) {
            throw new IllegalArgumentException(
                    "Complete source-consistent stability observation is required");
        }
    }

    private Optional<TestSuiteStabilityObservationLedgerHead> observationHeadByScope(
            String scopeFingerprint,
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        return observationHeadByScope(
                scopeFingerprint, tenantId, environmentId, suiteRef, true);
    }

    private Optional<TestSuiteStabilityObservationLedgerHead> observationHeadByScope(
            String scopeFingerprint,
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            boolean verifyRequestedScope) {
        List<StoredObservationHead> heads = jdbc.query("""
                SELECT tenant_id, environment_id, scope_fingerprint,
                       suite_id, suite_revision, suite_fingerprint,
                       coverage_from, latest_sequence, latest_observation_id,
                       latest_entry_fingerprint, updated_at, head_fingerprint
                FROM rg_test_suite_stability_observation_heads
                WHERE scope_fingerprint = ?
                """, (rs, row) -> new StoredObservationHead(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                readObservationHead(rs)), scopeFingerprint);
        if (heads.size() > 1) {
            throw new IllegalStateException("Stability observation head is not unique");
        }
        if (heads.isEmpty()) {
            if (observationRowsExist(scopeFingerprint)
                    || observationLifecycleRowsExist(scopeFingerprint)) {
                throw new IllegalStateException(
                        "Stability observation ledger rows or lifecycle state exist without a committed head");
            }
            return Optional.empty();
        }
        StoredObservationHead stored = heads.getFirst();
        TestSuiteStabilityObservationLedgerHead head = stored.head();
        if ((verifyRequestedScope && (!tenantId.equals(stored.tenantId())
                || !environmentId.equals(stored.environmentId())))
                || !scopeFingerprint.equals(head.scopeFingerprint())
                || !suiteRef.equals(head.suiteRef())) {
            throw new IllegalStateException(
                    "Stability observation head contradicts its indexed scope");
        }
        requireObservationHead(head);
        StoredObservationCoordinate latest = observationCoordinateAt(
                scopeFingerprint, head.latestSequence()).orElseThrow(() ->
                new IllegalStateException(
                        "Stability observation head has no latest ledger row"));
        if (!head.latestObservationId().equals(latest.observationId())
                || !head.latestEntryFingerprint().equals(latest.entryFingerprint())) {
            throw new IllegalStateException(
                    "Stability observation head contradicts its latest ledger row");
        }
        TestSuiteStabilityObservationLedgerFloor floor = observationFloorByScope(
                scopeFingerprint, stored.tenantId(), stored.environmentId(), suiteRef)
                .orElseThrow(() -> new IllegalStateException(
                        "Stability observation head has no committed floor"));
        if (!head.coverageFrom().equals(floor.coverageFrom())
                || floor.floorSequence() > head.latestSequence()) {
            throw new IllegalStateException(
                    "Stability observation head contradicts its committed floor");
        }
        return Optional.of(head);
    }

    private Optional<TestSuiteStabilityObservationLedgerFloor> observationFloorByScope(
            String scopeFingerprint,
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        return observationFloorByScope(
                scopeFingerprint, tenantId, environmentId, suiteRef, true);
    }

    private Optional<TestSuiteStabilityObservationLedgerFloor> observationFloorByScope(
            String scopeFingerprint,
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            boolean verifyRequestedScope) {
        List<StoredObservationFloor> floors = jdbc.query("""
                SELECT tenant_id, environment_id, scope_fingerprint,
                       suite_id, suite_revision, suite_fingerprint, floor_sequence,
                       previous_observation_id, previous_entry_fingerprint,
                       floor_observation_id, floor_entry_fingerprint, coverage_from,
                       retirement_generation, latest_retirement_id,
                       latest_retirement_fingerprint, updated_at, floor_fingerprint, floor_json
                FROM rg_test_suite_stability_observation_floors
                WHERE scope_fingerprint = ?
                """, (rs, row) -> new StoredObservationFloor(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                storedObservationFloor(rs, row)), scopeFingerprint);
        if (floors.size() > 1) {
            throw new IllegalStateException("Stability observation floor is not unique");
        }
        if (floors.isEmpty()) {
            return Optional.empty();
        }
        StoredObservationFloor stored = floors.getFirst();
        TestSuiteStabilityObservationLedgerFloor floor = stored.floor();
        if ((verifyRequestedScope && (!tenantId.equals(stored.tenantId())
                || !environmentId.equals(stored.environmentId())))
                || !scopeFingerprint.equals(floor.scopeFingerprint())
                || !suiteRef.equals(floor.suiteRef())) {
            throw new IllegalStateException(
                    "Stability observation floor contradicts its indexed scope");
        }
        if (!TestSuiteStabilityObservationLedgerFloorIntegrity.valid(objectMapper, floor)) {
            throw new IllegalStateException(
                    "Stability observation floor fingerprint is invalid");
        }
        if (!observationHeadRowsExist(scopeFingerprint)) {
            throw new IllegalStateException(
                    "Stability observation floor has no committed head");
        }
        StoredObservationCoordinate coordinate = observationCoordinateAt(
                scopeFingerprint, floor.floorSequence()).orElseThrow(() ->
                new IllegalStateException(
                        "Stability observation floor has no active ledger row"));
        if (!floor.floorObservationId().equals(coordinate.observationId())
                || !floor.floorEntryFingerprint().equals(coordinate.entryFingerprint())) {
            throw new IllegalStateException(
                    "Stability observation floor contradicts its active ledger row");
        }
        return Optional.of(floor);
    }

    private boolean observationRowsExist(String scopeFingerprint) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_suite_stability_observations
                WHERE scope_fingerprint = ?
                """, Long.class, scopeFingerprint);
        return count != null && count > 0;
    }

    private boolean observationHeadRowsExist(String scopeFingerprint) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_suite_stability_observation_heads
                WHERE scope_fingerprint = ?
                """, Long.class, scopeFingerprint);
        return count != null && count > 0;
    }

    private void requireCompleteObservationLifecycle(
            String scopeFingerprint,
            TestSuiteStabilityObservationLedgerFloor currentFloor) {
        List<ObservationLifecycleStats> values = jdbc.query("""
                SELECT
                    (SELECT COUNT(*)
                     FROM rg_test_suite_stability_observation_retirements
                     WHERE scope_fingerprint = ?) AS retirement_count,
                    (SELECT COALESCE(MIN(retirement_generation), 0)
                     FROM rg_test_suite_stability_observation_retirements
                     WHERE scope_fingerprint = ?) AS retirement_minimum,
                    (SELECT COALESCE(MAX(retirement_generation), 0)
                     FROM rg_test_suite_stability_observation_retirements
                     WHERE scope_fingerprint = ?) AS retirement_maximum,
                    (SELECT COUNT(*)
                     FROM rg_test_suite_stability_observation_archives
                     WHERE scope_fingerprint = ?) AS archive_count,
                    (SELECT COALESCE(MIN(retirement_generation), 0)
                     FROM rg_test_suite_stability_observation_archives
                     WHERE scope_fingerprint = ?) AS archive_minimum,
                    (SELECT COALESCE(MAX(retirement_generation), 0)
                     FROM rg_test_suite_stability_observation_archives
                     WHERE scope_fingerprint = ?) AS archive_maximum
                """, (rs, row) -> new ObservationLifecycleStats(
                rs.getLong("retirement_count"), rs.getLong("retirement_minimum"),
                rs.getLong("retirement_maximum"), rs.getLong("archive_count"),
                rs.getLong("archive_minimum"), rs.getLong("archive_maximum")),
                scopeFingerprint, scopeFingerprint, scopeFingerprint,
                scopeFingerprint, scopeFingerprint, scopeFingerprint);
        if (values.size() != 1) {
            throw new IllegalStateException(
                    "Stability observation lifecycle statistics are unavailable");
        }
        ObservationLifecycleStats stats = values.getFirst();
        long generation = currentFloor.retirementGeneration();
        long expectedMinimum = generation == 0 ? 0 : 1;
        if (stats.retirementCount() != generation
                || stats.retirementMinimum() != expectedMinimum
                || stats.retirementMaximum() != generation
                || stats.archiveCount() != generation
                || stats.archiveMinimum() != expectedMinimum
                || stats.archiveMaximum() != generation) {
            throw new IllegalStateException(
                    "Stability observation lifecycle generations are incomplete");
        }
        if (generation > 0) {
            TestSuiteStabilityObservationFloorRetirement latest =
                    findObservationFloorRetirement(currentFloor.latestRetirementId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Current floor has no latest retirement record"));
            if (latest.evidence().retirementGeneration() != generation
                    || !latest.retirementFingerprint().equals(
                    currentFloor.latestRetirementFingerprint())
                    || !TestSuiteStabilityObservationFloorRetirementIntegrity
                    .successorFloor(objectMapper, latest).equals(currentFloor)) {
                throw new IllegalStateException(
                        "Current floor contradicts its latest retirement record");
            }
        }
    }

    private boolean observationLifecycleRowsExist(String scopeFingerprint) {
        Long count = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*)
                     FROM rg_test_suite_stability_observation_floors
                     WHERE scope_fingerprint = ?)
                  + (SELECT COUNT(*)
                     FROM rg_test_suite_stability_observation_archives
                     WHERE scope_fingerprint = ?)
                  + (SELECT COUNT(*)
                     FROM rg_test_suite_stability_observation_retirements
                     WHERE scope_fingerprint = ?)
                """, Long.class, scopeFingerprint, scopeFingerprint, scopeFingerprint);
        return count != null && count > 0;
    }

    private TestSuiteStabilityObservationLedgerHead readObservationHead(ResultSet rs)
            throws SQLException {
        return new TestSuiteStabilityObservationLedgerHead(
                TestSuiteStabilityObservationLedgerHead.SCHEMA_VERSION,
                rs.getString("scope_fingerprint"),
                new TestSuiteExecutionRequest.SuiteRef(
                        rs.getString("suite_id"), rs.getLong("suite_revision"),
                        rs.getString("suite_fingerprint")),
                rs.getTimestamp("coverage_from").toInstant(),
                rs.getLong("latest_sequence"),
                rs.getString("latest_observation_id"),
                rs.getString("latest_entry_fingerprint"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("head_fingerprint"));
    }

    private void requireObservationHead(TestSuiteStabilityObservationLedgerHead head) {
        if (!TestSuiteStabilityObservationLedgerHeadIntegrity.valid(objectMapper, head)) {
            throw new IllegalStateException("Stability observation head fingerprint is invalid");
        }
    }

    private void requireObservationEntry(
            TestSuiteStabilityObservationLedgerEntry entry,
            String scopeFingerprint,
            long expectedSequence,
            String expectedPredecessor) {
        requireObservationShape(entry.observation());
        if (!entry.scopeFingerprint().equals(scopeFingerprint)
                || !entry.observation().evidence().scopeFingerprint().equals(scopeFingerprint)
                || entry.sequence() != expectedSequence
                || !entry.previousObservationId().equals(expectedPredecessor)
                || !TestSuiteStabilityObservationLedgerEntryIntegrity.valid(
                objectMapper, entry)) {
            throw new IllegalStateException("Stability observation ledger chain is invalid");
        }
    }

    private void requireObservationShape(TestSuiteStabilityObservation observation) {
        if (!observation.evidenceFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, observation.evidence()))
                || !observation.attestationFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, observation.attestation()))) {
            throw new IllegalStateException("Stability observation fingerprint is invalid");
        }
    }

    private Optional<String> observationIdAt(String scopeFingerprint, long sequence) {
        return observationCoordinateAt(scopeFingerprint, sequence)
                .map(StoredObservationCoordinate::observationId);
    }

    private Optional<StoredObservationCoordinate> observationCoordinateAt(
            String scopeFingerprint,
            long sequence) {
        List<StoredObservationCoordinate> values = jdbc.query("""
                SELECT observation_id, entry_fingerprint
                FROM rg_test_suite_stability_observations
                WHERE scope_fingerprint = ? AND ledger_sequence = ?
                """, (rs, row) -> new StoredObservationCoordinate(
                rs.getString("observation_id"), rs.getString("entry_fingerprint")),
                scopeFingerprint, sequence);
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Stability observation ledger sequence is not unique");
        }
        return values.stream().findFirst();
    }

    private Optional<TestSuiteStabilityObservationLedgerEntry> observationEntryAt(
            String scopeFingerprint,
            long sequence) {
        List<TestSuiteStabilityObservationLedgerEntry> values = jdbc.query("""
                SELECT observation_id, scope_fingerprint, ledger_sequence,
                       previous_observation_id, stability_run_id, source_created_at,
                       appended_at, observation_fingerprint, attestation_fingerprint,
                       entry_fingerprint, entry_json
                FROM rg_test_suite_stability_observations
                WHERE scope_fingerprint = ? AND ledger_sequence = ?
                """, this::storedObservationEntry, scopeFingerprint, sequence);
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Stability observation ledger sequence is not unique");
        }
        return values.stream().findFirst();
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

    private static void requireProgressMatchesLease(
            TestSuiteStabilityExecutionProgress progress,
            TestSuiteStabilityExecutionLease lease) {
        if (!progress.stabilityRunId().equals(lease.stabilityRunId())
                || !progress.tenantId().equals(lease.tenantId())
                || !progress.environmentId().equals(lease.environmentId())
                || !progress.clientRequestId().equals(lease.clientRequestId())
                || !progress.requestFingerprint().equals(lease.requestFingerprint())) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                    "Suite-stability progress does not match its execution lease");
        }
    }

    private static void requireTerminalMatchesProgress(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionProgress progress) {
        List<TestSuiteStabilityExecutionProgress.AttemptReference> terminalAttempts;
        try {
            terminalAttempts = record.evidence().attempts().stream()
                    .map(value -> new TestSuiteStabilityExecutionProgress.AttemptReference(
                            value.attempt(), value.suiteRunId(),
                            value.aggregateEvidenceFingerprint()))
                    .toList();
        } catch (IllegalArgumentException incomplete) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                    "Terminal stability evidence has no complete durable source closure");
        }
        boolean completeHorizon = progress.completedAttempts() == progress.plannedAttempts();
        boolean validSequentialEarlyTerminal = false;
        TestSuiteStabilityEvidence evidence = record.evidence();
        if (TestSuiteStabilityEvidence.SCHEMA_VERSION.equals(evidence.schemaVersion())
                && evidence.statisticalAssessment() != null) {
            validSequentialEarlyTerminal = List.of(
                    TestSuiteStabilityEvidence.StatisticalStopReason.E_VALUE_THRESHOLD_REACHED,
                    TestSuiteStabilityEvidence.StatisticalStopReason.CENSORING_OBSERVED)
                    .contains(evidence.statisticalAssessment().stopReason());
        }
        if (!record.evidence().suiteRef().equals(progress.suiteRef())
                || record.evidence().requestedAttempts() != progress.plannedAttempts()
                || progress.completedAttempts() != terminalAttempts.size()
                || !completeHorizon && !validSequentialEarlyTerminal
                || !terminalAttempts.equals(progress.attempts())) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                    "Terminal stability evidence contradicts durable parent progress");
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

    private static void requireSameProgressIntent(
            TestSuiteStabilityExecutionProgress progress,
            TestSuiteStabilityLeaseRequest request) {
        requireCompleteCoordinates(progress.stabilityRunId(), progress.requestFingerprint(),
                request.stabilityRunId(), request.requestFingerprint());
        if (!progress.suiteRef().equals(request.suiteRef())
                || !progress.classification().equals(request.classification())
                || progress.plannedAttempts() != request.plannedAttempts()) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT,
                    "Suite-stability progress represents a different immutable execution plan");
        }
    }

    private static void requireSameStopIntent(
            TestSuiteStabilityExecutionStop stop,
            TestSuiteStabilityLeaseRequest request) {
        requireCompleteCoordinates(stop.stabilityRunId(), stop.requestFingerprint(),
                request.stabilityRunId(), request.requestFingerprint());
        if (!stop.tenantId().equals(request.tenantId())
                || !stop.environmentId().equals(request.environmentId())
                || !stop.clientRequestId().equals(request.clientRequestId())
                || !stop.classification().equals(request.classification())) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT,
                    "Stopped suite-stability identity has a different classification");
        }
    }

    private static void requireSameStopIntent(
            TestSuiteStabilityExecutionStop stop,
            TestSuiteStabilityExecutionStopRequest request) {
        requireCompleteCoordinates(stop.stabilityRunId(), stop.requestFingerprint(),
                request.stabilityRunId(), request.requestFingerprint());
        if (!stop.tenantId().equals(request.tenantId())
                || !stop.environmentId().equals(request.environmentId())
                || !stop.clientRequestId().equals(request.clientRequestId())
                || !stop.classification().equals(request.classification())
                || stop.reason() != request.reason()
                || !stop.failureCode().equals(request.failureCode())
                || !stop.actorId().equals(request.actorId())
                || !Duration.between(stop.createdAt(), stop.expiresAt())
                .equals(request.retention())) {
            throw conflict(TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT,
                    "Suite-stability stop identity represents a different terminal intent");
        }
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

    private void lockObservationScope(String scopeFingerprint) {
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_observation_locks (scope_fingerprint)
                KEY(scope_fingerprint) VALUES (?)
                """, scopeFingerprint);
        List<String> locked = jdbc.query("""
                SELECT scope_fingerprint
                FROM rg_test_suite_stability_observation_locks
                WHERE scope_fingerprint = ? FOR UPDATE
                """, (rs, row) -> rs.getString("scope_fingerprint"), scopeFingerprint);
        if (locked.size() != 1) {
            throw new IllegalStateException("Stability observation scope lock is unavailable");
        }
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

    private String observationScopeFingerprint(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
        return ProtocolFingerprint.of(objectMapper,
                new ObservationScopeIdentity(tenantId, environmentId, suiteRef));
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

    private static Duration boundedProgressRetention(Duration value) {
        if (value == null
                || value.compareTo(TestSuiteStabilityLeaseRequest.MINIMUM_PROGRESS_RETENTION) < 0
                || value.compareTo(TestSuiteStabilityLeaseRequest.MAXIMUM_PROGRESS_RETENTION) > 0
                || value.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "Stability progress retention is outside bounded whole-second limits");
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

    private String writeObservationEntry(TestSuiteStabilityObservationLedgerEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize suite-stability observation entry", failure);
        }
    }

    private TestSuiteStabilityObservationLedgerEntry readObservationEntry(String value) {
        try {
            return objectMapper.readValue(
                    value, TestSuiteStabilityObservationLedgerEntry.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot deserialize suite-stability observation entry", failure);
        }
    }

    private TestSuiteStabilityObservationLedgerEntry storedObservationEntry(
            ResultSet result,
            int row) throws SQLException {
        TestSuiteStabilityObservationLedgerEntry entry = readObservationEntry(
                result.getString("entry_json"));
        if (!entry.observation().evidence().observationId().equals(
                result.getString("observation_id"))
                || !entry.scopeFingerprint().equals(result.getString("scope_fingerprint"))
                || entry.sequence() != result.getLong("ledger_sequence")
                || !entry.previousObservationId().equals(
                result.getString("previous_observation_id"))
                || !entry.observation().evidence().source().stabilityRunId().equals(
                result.getString("stability_run_id"))
                || !entry.observation().evidence().source().createdAt().equals(
                result.getTimestamp("source_created_at").toInstant())
                || !entry.appendedAt().equals(
                result.getTimestamp("appended_at").toInstant())
                || !entry.observation().evidenceFingerprint().equals(
                result.getString("observation_fingerprint"))
                || !entry.observation().attestationFingerprint().equals(
                result.getString("attestation_fingerprint"))
                || !entry.entryFingerprint().equals(
                result.getString("entry_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored suite-stability observation columns contradict its entry");
        }
        return entry;
    }

    private TestSuiteStabilityObservationLedgerFloor storedObservationFloor(
            ResultSet result,
            int row) throws SQLException {
        TestSuiteStabilityObservationLedgerFloor floor = readFloor(
                result.getString("floor_json"));
        if (!floor.scopeFingerprint().equals(result.getString("scope_fingerprint"))
                || !floor.suiteRef().suiteId().equals(result.getString("suite_id"))
                || floor.suiteRef().revision() != result.getLong("suite_revision")
                || !floor.suiteRef().fingerprint().equals(
                result.getString("suite_fingerprint"))
                || floor.floorSequence() != result.getLong("floor_sequence")
                || !floor.previousObservationId().equals(
                result.getString("previous_observation_id"))
                || !floor.previousEntryFingerprint().equals(
                result.getString("previous_entry_fingerprint"))
                || !floor.floorObservationId().equals(
                result.getString("floor_observation_id"))
                || !floor.floorEntryFingerprint().equals(
                result.getString("floor_entry_fingerprint"))
                || !floor.coverageFrom().equals(
                result.getTimestamp("coverage_from").toInstant())
                || floor.retirementGeneration()
                != result.getLong("retirement_generation")
                || !floor.latestRetirementId().equals(
                result.getString("latest_retirement_id"))
                || !floor.latestRetirementFingerprint().equals(
                result.getString("latest_retirement_fingerprint"))
                || !floor.updatedAt().equals(result.getTimestamp("updated_at").toInstant())
                || !floor.floorFingerprint().equals(
                result.getString("floor_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored suite-stability observation floor columns contradict its record");
        }
        return floor;
    }

    private TestSuiteStabilityObservationFloorRetirement storedRetirement(
            ResultSet result,
            int row) throws SQLException {
        TestSuiteStabilityObservationFloorRetirement retirement = readRetirement(
                result.getString("retirement_json"));
        if (!TestSuiteStabilityObservationFloorRetirementIntegrity.valid(
                objectMapper, retirement)
                || !retirement.evidence().retirementId().equals(
                result.getString("retirement_id"))
                || !retirement.evidence().scopeFingerprint().equals(
                result.getString("scope_fingerprint"))
                || retirement.evidence().retirementGeneration()
                != result.getLong("retirement_generation")
                || !retirement.evidenceFingerprint().equals(
                result.getString("evidence_fingerprint"))
                || !retirement.attestationFingerprint().equals(
                result.getString("attestation_fingerprint"))
                || !retirement.retirementFingerprint().equals(
                result.getString("retirement_fingerprint"))
                || !retirement.evidence().retiredAt().equals(
                result.getTimestamp("retired_at").toInstant())) {
            throw new IllegalStateException(
                    "Stored suite-stability observation retirement columns contradict its record");
        }
        return retirement;
    }

    private TestSuiteStabilityObservationExternalArchiveReceiptSet
            storedExternalArchiveReceiptSet(ResultSet result, int row) throws SQLException {
        TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet =
                readExternalArchiveReceiptSet(result.getString("receipt_set_json"));
        TestSuiteStabilityObservationFloorRetirement retirement =
                receiptSet.request().retirement();
        TestSuiteStabilityObservationFloorRetirementEvidence evidence = retirement.evidence();
        TestSuiteStabilityObservationArchiveSegment archive = evidence.archiveSegment();
        if (!TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                objectMapper, receiptSet)
                || !receiptSet.receiptSetId().equals(result.getString("receipt_set_id"))
                || !evidence.retirementId().equals(result.getString("retirement_id"))
                || !evidence.scopeFingerprint().equals(
                result.getString("scope_fingerprint"))
                || evidence.retirementGeneration()
                != result.getLong("retirement_generation")
                || !archive.segmentId().equals(result.getString("segment_id"))
                || !retirement.retirementFingerprint().equals(
                result.getString("retirement_fingerprint"))
                || !archive.segmentFingerprint().equals(
                result.getString("segment_fingerprint"))
                || !evidence.retentionPolicyFingerprint().equals(
                result.getString("retention_policy_fingerprint"))
                || !receiptSet.request().retainUntil().equals(
                result.getTimestamp("retain_until").toInstant())
                || receiptSet.requiredCopies() != result.getInt("required_copies")
                || receiptSet.receipts().size() != result.getInt("receipt_count")
                || !receiptSet.confirmedAt().equals(
                result.getTimestamp("confirmed_at").toInstant())
                || !receiptSet.receiptSetFingerprint().equals(
                result.getString("receipt_set_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored external observation archive receipt columns contradict its record");
        }
        return receiptSet;
    }

    private void requireStoredArchive(
            TestSuiteStabilityObservationArchiveSegment expected) {
        List<TestSuiteStabilityObservationArchiveSegment> values = jdbc.query("""
                SELECT segment_id, scope_fingerprint, retirement_generation,
                       from_sequence, through_sequence, segment_fingerprint,
                       archived_at, segment_json
                FROM rg_test_suite_stability_observation_archives
                WHERE segment_id = ?
                """, this::storedArchive, expected.segmentId());
        if (values.size() != 1 || !values.getFirst().equals(expected)) {
            throw new IllegalStateException(
                    "Signed stability observation retirement has no exact local archive");
        }
    }

    private TestSuiteStabilityObservationArchiveSegment storedArchive(
            ResultSet result,
            int row) throws SQLException {
        TestSuiteStabilityObservationArchiveSegment archive = readArchive(
                result.getString("segment_json"));
        if (!archive.segmentFingerprint().equals(
                TestSuiteStabilityObservationFloorRetirementIntegrity.archiveFingerprint(
                        objectMapper, archive))
                || !archive.segmentId().equals(result.getString("segment_id"))
                || !archive.scopeFingerprint().equals(
                result.getString("scope_fingerprint"))
                || archive.retirementGeneration()
                != result.getLong("retirement_generation")
                || archive.fromSequence() != result.getLong("from_sequence")
                || archive.throughSequence() != result.getLong("through_sequence")
                || !archive.segmentFingerprint().equals(
                result.getString("segment_fingerprint"))
                || !archive.archivedAt().equals(
                result.getTimestamp("archived_at").toInstant())) {
            throw new IllegalStateException(
                    "Stored stability observation archive columns contradict its segment");
        }
        return archive;
    }

    private String writeFloor(TestSuiteStabilityObservationLedgerFloor floor) {
        try {
            return objectMapper.writeValueAsString(floor);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize suite-stability observation floor", failure);
        }
    }

    private TestSuiteStabilityObservationLedgerFloor readFloor(String value) {
        try {
            return objectMapper.readValue(
                    value, TestSuiteStabilityObservationLedgerFloor.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot deserialize suite-stability observation floor", failure);
        }
    }

    private String writeArchive(TestSuiteStabilityObservationArchiveSegment archive) {
        try {
            return objectMapper.writeValueAsString(archive);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize suite-stability observation archive", failure);
        }
    }

    private String writeExternalArchiveReceiptSet(
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
        try {
            return objectMapper.writeValueAsString(receiptSet);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize external observation archive receipt set", failure);
        }
    }

    private TestSuiteStabilityObservationExternalArchiveReceiptSet
            readExternalArchiveReceiptSet(String value) {
        try {
            return objectMapper.readValue(
                    value, TestSuiteStabilityObservationExternalArchiveReceiptSet.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot deserialize external observation archive receipt set", failure);
        }
    }

    private TestSuiteStabilityObservationArchiveSegment readArchive(String value) {
        try {
            return objectMapper.readValue(
                    value, TestSuiteStabilityObservationArchiveSegment.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot deserialize suite-stability observation archive", failure);
        }
    }

    private String writeRetirement(TestSuiteStabilityObservationFloorRetirement retirement) {
        try {
            return objectMapper.writeValueAsString(retirement);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize suite-stability observation retirement", failure);
        }
    }

    private TestSuiteStabilityObservationFloorRetirement readRetirement(String value) {
        try {
            return objectMapper.readValue(
                    value, TestSuiteStabilityObservationFloorRetirement.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot deserialize suite-stability observation retirement", failure);
        }
    }

    private String writeStop(TestSuiteStabilityExecutionStop stop) {
        try {
            return objectMapper.writeValueAsString(stop);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize suite-stability execution stop", failure);
        }
    }

    private String writeAttempts(
            List<TestSuiteStabilityExecutionProgress.AttemptReference> attempts) {
        try {
            return objectMapper.writeValueAsString(attempts);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize suite-stability progress journal", failure);
        }
    }

    private List<TestSuiteStabilityExecutionProgress.AttemptReference> readAttempts(String value) {
        try {
            return objectMapper.readValue(value, ATTEMPT_REFERENCES);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Stored suite-stability progress journal is corrupt", failure);
        }
    }

    private TestSuiteStabilityRunRecord read(String value) {
        try {
            return objectMapper.readValue(value, TestSuiteStabilityRunRecord.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored stability analysis is corrupt", failure);
        }
    }

    private TestSuiteStabilityExecutionStop readStop(String value) {
        try {
            TestSuiteStabilityExecutionStop stop = objectMapper.readValue(
                    value, TestSuiteStabilityExecutionStop.class);
            String expected = stopFingerprint(stop.stabilityRunId(), stop.tenantId(),
                    stop.environmentId(), stop.clientRequestId(), stop.requestFingerprint(),
                    stop.classification(), stop.reason(), stop.failureCode(), stop.actorId(),
                    stop.createdAt(), stop.expiresAt());
            if (!expected.equals(stop.recordFingerprint())) {
                throw new IllegalStateException(
                        "Stored suite-stability execution stop fingerprint is invalid");
            }
            return stop;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Stored suite-stability execution stop is corrupt", failure);
        }
    }

    private TestSuiteStabilityExecutionStop storedStop(ResultSet result, int row)
            throws SQLException {
        TestSuiteStabilityExecutionStop stop = readStop(result.getString("stop_json"));
        if (!stop.stabilityRunId().equals(result.getString("stability_run_id"))
                || !stop.tenantId().equals(result.getString("tenant_id"))
                || !stop.environmentId().equals(result.getString("environment_id"))
                || !stop.clientRequestId().equals(result.getString("client_request_id"))
                || !stop.requestFingerprint().equals(result.getString("request_fingerprint"))
                || !stop.reason().name().equals(result.getString("reason"))
                || !stop.createdAt().equals(result.getTimestamp("created_at").toInstant())
                || !stop.expiresAt().equals(result.getTimestamp("expires_at").toInstant())
                || !stop.recordFingerprint().equals(result.getString("record_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored suite-stability execution stop columns contradict its record");
        }
        return stop;
    }

    private String stopFingerprint(
            String stabilityRunId,
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String classification,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode,
            String actorId,
            Instant createdAt,
            Instant expiresAt) {
        return ProtocolFingerprint.of(objectMapper, new ExecutionStopFingerprintMaterial(
                "bloge.testSuiteStabilityExecutionStop.v1", stabilityRunId, tenantId,
                environmentId, clientRequestId, requestFingerprint, classification, reason,
                failureCode, actorId, createdAt, expiresAt));
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("Stability JDBC adapter requires a datasource");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private static String zeroRetirementId() {
        return "stability-observation-retirement-" + "0".repeat(64);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record ObservationScopeIdentity(
            String tenantId,
            String environmentId,
            TestSuiteExecutionRequest.SuiteRef suiteRef) {
    }

    private record StoredObservationHead(
            String tenantId,
            String environmentId,
            TestSuiteStabilityObservationLedgerHead head) {
    }

    private record StoredObservationFloor(
            String tenantId,
            String environmentId,
            TestSuiteStabilityObservationLedgerFloor floor) {
    }

    private record StoredObservationCoordinate(
            String observationId,
            String entryFingerprint) {
    }

    private record ObservationLifecycleStats(
            long retirementCount,
            long retirementMinimum,
            long retirementMaximum,
            long archiveCount,
            long archiveMinimum,
            long archiveMaximum) {
    }

    private record LeaseCandidate(
            String stabilityRunId,
            String tenantId,
            String environmentId,
            String clientRequestId) {
    }

    private record ExecutionStopFingerprintMaterial(
            String schemaVersion,
            String stabilityRunId,
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String classification,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode,
            String actorId,
            Instant createdAt,
            Instant expiresAt) {
    }
}
