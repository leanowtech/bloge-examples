package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.ActiveWorkerCandidateQuarantine;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Database-authoritative maintenance control plane for exact-checkpoint worker quarantines.
 *
 * <p>The automatic worker path owns the immutable quarantine fact. This control plane stores
 * operational ownership in a separate, cascading row so maintenance cannot silently rewrite the
 * failure reason, threshold, or checkpoint binding. Every read revalidates both the automatic
 * fact and any maintenance projection before returning payload-free metadata.</p>
 */
public final class DatabaseDurableWorkerQuarantineControlPlane {

    private static final String RETENTION_JOB_NAME = "bloge-worker-quarantine-retention";
    private static final int MAX_PAGE_SIZE = 1_000;
    private static final int TOKEN_MIGRATION_PAGE_SIZE = 1_000;
    private static final int CONTROL_RECORD_VERSION = 2;
    private static final int TOMBSTONE_RECORD_VERSION = 2;
    private static final Duration MIN_COMMAND_RETENTION = Duration.ofHours(1);
    private static final Duration MIN_HISTORY_RETENTION = Duration.ofDays(1);
    private static final Duration MIN_TOMBSTONE_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3_650);
    private static final Pattern REASON_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DurableTestExecutionCheckpointIntegrity checkpointIntegrity;
    private final WorkerQuarantineClaimTokenProtector claimTokenProtector;
    private final WorkerQuarantineRequestKeyProtector requestKeyProtector;
    private final TransactionTemplate transactions;
    private final TransactionTemplate observations;
    private final String retentionOwnerId;
    private final Duration retentionLeaseDuration;

    /**
     * Creates a quarantine maintenance authority over the isolated test-runtime database.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager for the same datasource
     * @param objectMapper canonical protocol fingerprint mapper
     * @param claimTokenProtector rotation-aware claim-command token envelope authority
     * @param requestKeyProtector independent rotation-aware idempotency index authority
     */
    public DatabaseDurableWorkerQuarantineControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            WorkerQuarantineClaimTokenProtector claimTokenProtector,
            WorkerQuarantineRequestKeyProtector requestKeyProtector) {
        this(jdbc, transactionManager, objectMapper, claimTokenProtector, requestKeyProtector,
                "worker-quarantine-retention-" + UUID.randomUUID(), Duration.ofMinutes(2));
    }

    /**
     * Creates a quarantine authority with an explicit cross-replica retention identity.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager for the same datasource
     * @param objectMapper canonical protocol fingerprint mapper
     * @param claimTokenProtector rotation-aware claim-command token envelope authority
     * @param requestKeyProtector independent rotation-aware idempotency index authority
     * @param retentionOwnerId stable identity of this Resource Gateway replica
     * @param retentionLeaseDuration database-clock lease for one bounded retention page
     */
    public DatabaseDurableWorkerQuarantineControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            WorkerQuarantineClaimTokenProtector claimTokenProtector,
            WorkerQuarantineRequestKeyProtector requestKeyProtector,
            String retentionOwnerId,
            Duration retentionLeaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.checkpointIntegrity = new DurableTestExecutionCheckpointIntegrity(objectMapper);
        this.claimTokenProtector = Objects.requireNonNull(
                claimTokenProtector, "claimTokenProtector");
        this.requestKeyProtector = Objects.requireNonNull(
                requestKeyProtector, "requestKeyProtector");
        PlatformTransactionManager safeTransactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(safeTransactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.observations = new TransactionTemplate(safeTransactionManager);
        this.observations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.observations.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.retentionOwnerId = required(retentionOwnerId, "retentionOwnerId", 255);
        this.retentionLeaseDuration = boundedRetentionLease(retentionLeaseDuration);
    }

    /** Creates maintenance ownership state without modifying automatic quarantine facts. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_controls (
                    scope_key VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    control_state VARCHAR(32) NOT NULL,
                    claim_owner VARCHAR(255) NOT NULL,
                    claim_token VARCHAR(255) NOT NULL,
                    claim_token_key_id VARCHAR(64) NOT NULL DEFAULT '',
                    claim_token_mac VARCHAR(80) NOT NULL DEFAULT '',
                    claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    control_version BIGINT NOT NULL,
                    record_version INTEGER NOT NULL DEFAULT 1,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, run_id),
                    CONSTRAINT fk_rg_test_worker_quarantine_control
                        FOREIGN KEY (scope_key, run_id)
                        REFERENCES rg_test_durable_worker_candidate_quarantines
                            (scope_key, run_id)
                        ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_worker_quarantine_controls
                ADD COLUMN IF NOT EXISTS claim_token_key_id VARCHAR(64) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_worker_quarantine_controls
                ADD COLUMN IF NOT EXISTS claim_token_mac VARCHAR(80) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_worker_quarantine_controls
                ADD COLUMN IF NOT EXISTS record_version INTEGER NOT NULL DEFAULT 1
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_control_migration_idx
                ON rg_test_durable_worker_quarantine_controls
                    (record_version, control_state, claim_token_key_id, scope_key, run_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_claim_commands (
                    scope_key VARCHAR(80) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    claim_owner VARCHAR(255) NOT NULL,
                    claim_duration_seconds BIGINT NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    result_claim_token VARCHAR(255) NOT NULL DEFAULT '',
                    result_claim_token_envelope VARCHAR(1024) NOT NULL DEFAULT '',
                    result_version BIGINT NOT NULL,
                    result_claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, client_request_id)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_worker_quarantine_claim_commands
                ADD COLUMN IF NOT EXISTS result_claim_token_envelope
                    VARCHAR(1024) NOT NULL DEFAULT ''
                """);
        migrateAndRewrapClaimCommandTokens();
        migrateAndRekeyActiveControlFences();
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_control_queue_idx
                ON rg_test_durable_worker_quarantine_controls
                    (control_state, claim_until, scope_key, run_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_resolutions (
                    scope_key VARCHAR(80) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    resolution_owner VARCHAR(255) NOT NULL,
                    resolution_action VARCHAR(32) NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    result_version BIGINT NOT NULL,
                    result_acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    result_receipt_fingerprint VARCHAR(80) NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, client_request_id),
                    CONSTRAINT uq_rg_test_worker_quarantine_receipt
                        UNIQUE (result_receipt_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_history (
                    history_id VARCHAR(36) PRIMARY KEY,
                    scope_key VARCHAR(80) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    quarantine_reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    quarantine_threshold INT NOT NULL,
                    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution_action VARCHAR(32) NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    resolution_owner VARCHAR(255) NOT NULL,
                    result_version BIGINT NOT NULL,
                    acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    receipt_fingerprint VARCHAR(80) NOT NULL UNIQUE,
                    record_fingerprint VARCHAR(80) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_history_scope_idx
                ON rg_test_durable_worker_quarantine_history
                    (scope_key, acted_at, history_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_discard_approvals (
                    scope_key VARCHAR(80) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    approval_id VARCHAR(36) NOT NULL UNIQUE,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    claim_owner VARCHAR(255) NOT NULL,
                    claim_version BIGINT NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    approver_id VARCHAR(255) NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    approval_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    approval_state VARCHAR(32) NOT NULL,
                    consumed_by_request_id VARCHAR(255) NOT NULL,
                    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    approval_fingerprint VARCHAR(80) NOT NULL UNIQUE,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_discard_approval_idx
                ON rg_test_durable_worker_quarantine_discard_approvals
                    (scope_key, approval_state, approval_until, approval_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_discards (
                    scope_key VARCHAR(80) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    resolution_owner VARCHAR(255) NOT NULL,
                    claim_version BIGINT NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    approval_id VARCHAR(36) NOT NULL,
                    approver_id VARCHAR(255) NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    result_version BIGINT NOT NULL,
                    result_acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    result_approval_fingerprint VARCHAR(80) NOT NULL,
                    result_receipt_fingerprint VARCHAR(80) NOT NULL UNIQUE,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_discard_history (
                    history_id VARCHAR(36) PRIMARY KEY,
                    scope_key VARCHAR(80) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    quarantine_reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    quarantine_threshold INT NOT NULL,
                    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    resolution_owner VARCHAR(255) NOT NULL,
                    approval_id VARCHAR(36) NOT NULL,
                    approver_id VARCHAR(255) NOT NULL,
                    approval_fingerprint VARCHAR(80) NOT NULL,
                    result_version BIGINT NOT NULL,
                    acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    receipt_fingerprint VARCHAR(80) NOT NULL UNIQUE,
                    record_fingerprint VARCHAR(80) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_discard_history_scope_idx
                ON rg_test_durable_worker_quarantine_discard_history
                    (scope_key, acted_at, history_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_claim_retention_idx
                ON rg_test_durable_worker_quarantine_claim_commands
                    (result_claim_until, scope_key, client_request_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_resolution_retention_idx
                ON rg_test_durable_worker_quarantine_resolutions
                    (result_acted_at, scope_key, client_request_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_approval_retention_idx
                ON rg_test_durable_worker_quarantine_discard_approvals
                    (approval_until, scope_key, client_request_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_discard_retention_idx
                ON rg_test_durable_worker_quarantine_discards
                    (result_acted_at, scope_key, client_request_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_history_retention_idx
                ON rg_test_durable_worker_quarantine_history (acted_at, history_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_discard_history_retention_idx
                ON rg_test_durable_worker_quarantine_discard_history (acted_at, history_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_request_tombstones (
                    request_kind VARCHAR(32) NOT NULL,
                    scope_key VARCHAR(80) NOT NULL,
                    request_key_id VARCHAR(64) NOT NULL DEFAULT '',
                    request_key VARCHAR(80) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    source_record_fingerprint VARCHAR(80) NOT NULL,
                    source_completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    tombstoned_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_version INTEGER NOT NULL DEFAULT 1,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (request_kind, scope_key, request_key)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_worker_quarantine_request_tombstones
                ADD COLUMN IF NOT EXISTS request_key_id VARCHAR(64) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_worker_quarantine_request_tombstones
                ADD COLUMN IF NOT EXISTS record_version INTEGER NOT NULL DEFAULT 1
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_tombstone_lookup_idx
                ON rg_test_durable_worker_quarantine_request_tombstones
                    (request_kind, scope_key, request_key_id, request_key)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_tombstone_expiry_idx
                ON rg_test_durable_worker_quarantine_request_tombstones
                    (expires_at, request_kind, scope_key, request_key)
                """);
        validateRequestTombstoneKeyRing();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_retention (
                    job_name VARCHAR(128) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    revision BIGINT NOT NULL,
                    total_tombstoned BIGINT NOT NULL,
                    total_tombstones_purged BIGINT NOT NULL,
                    total_history_purged BIGINT NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL
                )
                """);
        initializeRetentionState();
    }

    /**
     * Processes one cross-replica-fenced retention page for maintenance commands and history.
     *
     * <p>Each source category is independently bounded by {@code pageSize}. Command and approval
     * rows first become payload-free request tombstones, preserving an explicit idempotency window
     * after exact response replay has expired. History and expired tombstones are then physically
     * removed. Every source verification, tombstone insert, source delete, purge, and counter
     * checkpoint commits in one transaction; any corrupt row rolls the whole page back.</p>
     *
     * @param commandRetention time to retain exact command/approval replay after its deadline
     * @param historyRetention time to retain token-free operator and maker-checker history
     * @param tombstoneRetention idempotency protection after detailed command removal
     * @param pageSize per-category bound from 1 through 1,000
     * @return committed aggregate or lease-busy result
     */
    public RetentionAttempt retainPage(
            Duration commandRetention,
            Duration historyRetention,
            Duration tombstoneRetention,
            int pageSize) {
        Duration safeCommandRetention = boundedRetention(
                commandRetention, MIN_COMMAND_RETENTION, "commandRetention");
        Duration safeHistoryRetention = boundedRetention(
                historyRetention, MIN_HISTORY_RETENTION, "historyRetention");
        Duration safeTombstoneRetention = boundedRetention(
                tombstoneRetention, MIN_TOMBSTONE_RETENTION, "tombstoneRetention");
        int safePageSize = bounded(pageSize);
        Optional<RetentionLease> lease = acquireRetentionLease();
        if (lease.isEmpty()) {
            return RetentionAttempt.busy();
        }
        try {
            return retainClaimedPage(lease.orElseThrow(), safeCommandRetention,
                    safeHistoryRetention, safeTombstoneRetention, safePageSize);
        } catch (RuntimeException failure) {
            try {
                releaseRetentionLease(lease.orElseThrow());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /**
     * Returns aggregate retention ownership and backlog counts without request IDs or credentials.
     *
     * @return database-clock retention control snapshot
     */
    public RetentionSnapshot retentionSnapshot() {
        RetentionSnapshot snapshot = observations.execute(status -> {
            RetentionState state = requireRetentionState(false);
            Long tombstones = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_durable_worker_quarantine_request_tombstones
                    """, Long.class);
            return state.snapshot(tombstones == null ? 0L : tombstones);
        });
        if (snapshot == null) {
            throw new IllegalStateException("Worker quarantine retention snapshot returned no result");
        }
        return snapshot;
    }

    Optional<RetentionLease> acquireRetentionLease() {
        Optional<RetentionLease> result = transactions.execute(status -> {
            RetentionState current = requireRetentionState(true);
            Instant now = databaseNow();
            if (current.leaseUntil().isAfter(now)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            long epoch = Math.addExact(current.leaseEpoch(), 1);
            long revision = Math.addExact(current.revision(), 1);
            Instant leaseUntil = now.plus(retentionLeaseDuration);
            RetentionState next = new RetentionState(retentionOwnerId, token, epoch,
                    leaseUntil, revision, current.totalTombstoned(),
                    current.totalTombstonesPurged(), current.totalHistoryPurged(),
                    current.lastSuccessAt(), now, "");
            next = next.withFingerprint(retentionStateFingerprint(next));
            int updated = jdbc.update("""
                    UPDATE rg_test_durable_worker_quarantine_retention
                    SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                        revision = ?, updated_at = ?, record_fingerprint = ?
                    WHERE job_name = ? AND revision = ? AND record_fingerprint = ?
                    """, next.leaseOwner(), next.leaseToken(), next.leaseEpoch(),
                    Timestamp.from(next.leaseUntil()), next.revision(),
                    Timestamp.from(next.updatedAt()), next.recordFingerprint(),
                    RETENTION_JOB_NAME, current.revision(), current.recordFingerprint());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Worker quarantine retention lease fence was rejected");
            }
            return Optional.of(new RetentionLease(next.leaseOwner(), next.leaseToken(),
                    next.leaseEpoch(), next.leaseUntil()));
        });
        if (result == null) {
            throw new IllegalStateException(
                    "Worker quarantine retention lease acquisition returned no result");
        }
        return result;
    }

    RetentionAttempt retainClaimedPage(
            RetentionLease lease,
            Duration commandRetention,
            Duration historyRetention,
            Duration tombstoneRetention,
            int pageSize) {
        RetentionLease safeLease = Objects.requireNonNull(lease, "lease");
        RetentionAttempt result = transactions.execute(status -> {
            RetentionState state = requireRetentionState(true);
            Instant startedAt = databaseNow();
            requireLiveRetentionFence(state, safeLease, startedAt);
            Instant commandCutoff = startedAt.minus(commandRetention);
            Instant historyCutoff = startedAt.minus(historyRetention);
            int tombstoned = 0;
            tombstoned = Math.addExact(tombstoned, tombstoneClaimCommands(
                    commandCutoff, tombstoneRetention, pageSize, startedAt));
            tombstoned = Math.addExact(tombstoned, tombstoneResolutionCommands(
                    commandCutoff, tombstoneRetention, pageSize, startedAt));
            tombstoned = Math.addExact(tombstoned, tombstoneDiscardApprovals(
                    commandCutoff, tombstoneRetention, pageSize, startedAt));
            tombstoned = Math.addExact(tombstoned, tombstoneApprovedDiscardCommands(
                    commandCutoff, tombstoneRetention, pageSize, startedAt));
            int historiesPurged = purgeResolutionHistory(historyCutoff, pageSize);
            historiesPurged = Math.addExact(historiesPurged,
                    purgeApprovedDiscardHistory(historyCutoff, pageSize));
            int tombstonesPurged = purgeExpiredTombstones(startedAt, pageSize);
            Instant completedAt = databaseNow();
            requireLiveRetentionFence(state, safeLease, completedAt);
            long revision = Math.addExact(state.revision(), 1);
            RetentionState next = new RetentionState("", "", state.leaseEpoch(),
                    Instant.EPOCH, revision,
                    Math.addExact(state.totalTombstoned(), tombstoned),
                    Math.addExact(state.totalTombstonesPurged(), tombstonesPurged),
                    Math.addExact(state.totalHistoryPurged(), historiesPurged),
                    completedAt, completedAt, "");
            next = next.withFingerprint(retentionStateFingerprint(next));
            int updated = jdbc.update("""
                    UPDATE rg_test_durable_worker_quarantine_retention
                    SET lease_owner = '', lease_token = '', lease_until = ?, revision = ?,
                        total_tombstoned = ?, total_tombstones_purged = ?,
                        total_history_purged = ?, last_success_at = ?, updated_at = ?,
                        record_fingerprint = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_epoch = ? AND revision = ? AND record_fingerprint = ?
                    """, Timestamp.from(next.leaseUntil()), next.revision(),
                    next.totalTombstoned(), next.totalTombstonesPurged(),
                    next.totalHistoryPurged(), Timestamp.from(next.lastSuccessAt()),
                    Timestamp.from(next.updatedAt()), next.recordFingerprint(),
                    RETENTION_JOB_NAME, safeLease.ownerId(), safeLease.token(),
                    safeLease.epoch(), state.revision(), state.recordFingerprint());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Worker quarantine retention checkpoint fence was rejected");
            }
            return RetentionAttempt.completed(new RetentionResult(
                    tombstoned, tombstonesPurged, historiesPurged, completedAt));
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine retention returned no result");
        }
        return result;
    }

    private int tombstoneClaimCommands(
            Instant cutoff, Duration tombstoneRetention, int pageSize, Instant tombstonedAt) {
        List<StoredClaimCommand> commands = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               claim_owner, claim_duration_seconds, request_fingerprint,
                               result_claim_token, result_claim_token_envelope,
                               result_version, result_claim_until, created_at,
                               record_fingerprint
                        FROM rg_test_durable_worker_quarantine_claim_commands
                        WHERE result_claim_until <= ?
                        ORDER BY result_claim_until, scope_key, client_request_id
                        LIMIT ? FOR UPDATE
                        """, this::mapClaimCommand, Timestamp.from(cutoff), pageSize);
        for (StoredClaimCommand command : commands) {
            requireValid(command);
            claimFrom(command);
            insertRequestTombstone(RequestKind.CLAIM, command.scopeKey(),
                    command.clientRequestId(), command.requestFingerprint(),
                    command.recordFingerprint(), command.resultClaimUntil(), tombstonedAt,
                    tombstoneRetention);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_claim_commands
                    WHERE scope_key = ? AND client_request_id = ?
                      AND result_claim_until = ? AND record_fingerprint = ?
                    """, command.scopeKey(), command.clientRequestId(),
                    Timestamp.from(command.resultClaimUntil()), command.recordFingerprint());
            requireDeleted(deleted, "claim command");
        }
        return commands.size();
    }

    private int tombstoneResolutionCommands(
            Instant cutoff, Duration tombstoneRetention, int pageSize, Instant tombstonedAt) {
        List<StoredResolutionCommand> commands = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               resolution_owner, resolution_action, reason_code,
                               request_fingerprint, result_version, result_acted_at,
                               result_receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_resolutions
                        WHERE result_acted_at <= ?
                        ORDER BY result_acted_at, scope_key, client_request_id
                        LIMIT ? FOR UPDATE
                        """, this::mapResolutionCommand, Timestamp.from(cutoff), pageSize);
        for (StoredResolutionCommand command : commands) {
            requireValid(command);
            insertRequestTombstone(RequestKind.RESOLUTION, command.scopeKey(),
                    command.clientRequestId(), command.requestFingerprint(),
                    command.recordFingerprint(), command.resultActedAt(), tombstonedAt,
                    tombstoneRetention);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_resolutions
                    WHERE scope_key = ? AND client_request_id = ?
                      AND result_acted_at = ? AND record_fingerprint = ?
                    """, command.scopeKey(), command.clientRequestId(),
                    Timestamp.from(command.resultActedAt()), command.recordFingerprint());
            requireDeleted(deleted, "resolution command");
        }
        return commands.size();
    }

    private int tombstoneDiscardApprovals(
            Instant cutoff, Duration tombstoneRetention, int pageSize, Instant tombstonedAt) {
        List<StoredDiscardApproval> approvals = jdbc.query(discardApprovalSelect() + """
                        WHERE approval_until <= ?
                        ORDER BY approval_until, scope_key, client_request_id
                        LIMIT ? FOR UPDATE
                        """, this::mapDiscardApproval, Timestamp.from(cutoff), pageSize);
        for (StoredDiscardApproval approval : approvals) {
            requireValid(approval);
            insertRequestTombstone(RequestKind.DISCARD_APPROVAL, approval.scopeKey(),
                    approval.clientRequestId(), approval.requestFingerprint(),
                    approval.recordFingerprint(), approval.approvalUntil(), tombstonedAt,
                    tombstoneRetention);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_discard_approvals
                    WHERE scope_key = ? AND client_request_id = ? AND approval_until = ?
                      AND record_fingerprint = ?
                    """, approval.scopeKey(), approval.clientRequestId(),
                    Timestamp.from(approval.approvalUntil()), approval.recordFingerprint());
            requireDeleted(deleted, "discard approval");
        }
        return approvals.size();
    }

    private int tombstoneApprovedDiscardCommands(
            Instant cutoff, Duration tombstoneRetention, int pageSize, Instant tombstonedAt) {
        List<StoredApprovedDiscardCommand> commands = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               resolution_owner, claim_version, claim_until, approval_id,
                               approver_id, reason_code, request_fingerprint, result_version,
                               result_acted_at, result_approval_fingerprint,
                               result_receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_discards
                        WHERE result_acted_at <= ?
                        ORDER BY result_acted_at, scope_key, client_request_id
                        LIMIT ? FOR UPDATE
                        """, this::mapApprovedDiscardCommand, Timestamp.from(cutoff), pageSize);
        for (StoredApprovedDiscardCommand command : commands) {
            requireValid(command);
            insertRequestTombstone(RequestKind.APPROVED_DISCARD, command.scopeKey(),
                    command.clientRequestId(), command.requestFingerprint(),
                    command.recordFingerprint(), command.resultActedAt(), tombstonedAt,
                    tombstoneRetention);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_discards
                    WHERE scope_key = ? AND client_request_id = ?
                      AND result_acted_at = ? AND record_fingerprint = ?
                    """, command.scopeKey(), command.clientRequestId(),
                    Timestamp.from(command.resultActedAt()), command.recordFingerprint());
            requireDeleted(deleted, "approved discard command");
        }
        return commands.size();
    }

    private int purgeResolutionHistory(Instant cutoff, int pageSize) {
        List<QuarantineHistoryRecord> history = jdbc.query("""
                        SELECT history_id, scope_key, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               quarantine_reason, consecutive_failures, quarantine_threshold,
                               first_observed_at, quarantined_at, resolution_action, reason_code,
                               resolution_owner, result_version, acted_at, receipt_fingerprint,
                               record_fingerprint
                        FROM rg_test_durable_worker_quarantine_history
                        WHERE acted_at <= ?
                        ORDER BY acted_at, history_id
                        LIMIT ? FOR UPDATE
                        """, this::mapHistory, Timestamp.from(cutoff), pageSize);
        for (QuarantineHistoryRecord record : history) {
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_history
                    WHERE history_id = ? AND acted_at = ? AND record_fingerprint = ?
                    """, record.historyId(), Timestamp.from(record.actedAt()),
                    record.recordFingerprint());
            requireDeleted(deleted, "resolution history");
        }
        return history.size();
    }

    private int purgeApprovedDiscardHistory(Instant cutoff, int pageSize) {
        List<ApprovedDiscardHistoryRecord> history = jdbc.query("""
                        SELECT history_id, scope_key, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               quarantine_reason, consecutive_failures, quarantine_threshold,
                               first_observed_at, quarantined_at, reason_code, resolution_owner,
                               approval_id, approver_id, approval_fingerprint, result_version,
                               acted_at, receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_discard_history
                        WHERE acted_at <= ?
                        ORDER BY acted_at, history_id
                        LIMIT ? FOR UPDATE
                        """, this::mapApprovedDiscardHistory, Timestamp.from(cutoff), pageSize);
        for (ApprovedDiscardHistoryRecord record : history) {
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_discard_history
                    WHERE history_id = ? AND acted_at = ? AND record_fingerprint = ?
                    """, record.historyId(), Timestamp.from(record.actedAt()),
                    record.recordFingerprint());
            requireDeleted(deleted, "approved discard history");
        }
        return history.size();
    }

    private int purgeExpiredTombstones(Instant now, int pageSize) {
        List<StoredRequestTombstone> tombstones = jdbc.query("""
                        SELECT request_kind, scope_key, request_key_id, request_key,
                               request_fingerprint,
                               source_record_fingerprint, source_completed_at, tombstoned_at,
                               expires_at, record_version, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_request_tombstones
                        WHERE expires_at <= ?
                        ORDER BY expires_at, request_kind, scope_key, request_key
                        LIMIT ? FOR UPDATE
                        """, this::mapRequestTombstone, Timestamp.from(now), pageSize);
        for (StoredRequestTombstone tombstone : tombstones) {
            requireValid(tombstone);
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_durable_worker_quarantine_request_tombstones
                    WHERE request_kind = ? AND scope_key = ? AND request_key_id = ?
                      AND request_key = ?
                      AND expires_at = ? AND record_fingerprint = ?
                    """, tombstone.kind().name(), tombstone.scopeKey(),
                    tombstone.requestKeyId(), tombstone.requestKey(),
                    Timestamp.from(tombstone.expiresAt()),
                    tombstone.recordFingerprint());
            requireDeleted(deleted, "request tombstone");
        }
        return tombstones.size();
    }

    private void insertRequestTombstone(
            RequestKind kind,
            String scopeKey,
            String requestId,
            String requestFingerprint,
            String sourceRecordFingerprint,
            Instant sourceCompletedAt,
            Instant tombstonedAt,
            Duration retention) {
        WorkerQuarantineRequestKeyProtector.IndexKey index = requestKeyProtector.protect(
                kind.name(), scopeKey, requestId);
        StoredRequestTombstone unsealed = new StoredRequestTombstone(kind, scopeKey,
                index.keyId(), index.value(), requestFingerprint,
                sourceRecordFingerprint, sourceCompletedAt, tombstonedAt,
                tombstonedAt.plus(retention), TOMBSTONE_RECORD_VERSION, "");
        StoredRequestTombstone tombstone = unsealed.withFingerprint(
                requestTombstoneFingerprint(unsealed));
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_request_tombstones (
                    request_kind, scope_key, request_key_id, request_key, request_fingerprint,
                    source_record_fingerprint, source_completed_at, tombstoned_at,
                    expires_at, record_version, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tombstone.kind().name(), tombstone.scopeKey(),
                tombstone.requestKeyId(), tombstone.requestKey(),
                tombstone.requestFingerprint(),
                tombstone.sourceRecordFingerprint(), Timestamp.from(tombstone.sourceCompletedAt()),
                Timestamp.from(tombstone.tombstonedAt()), Timestamp.from(tombstone.expiresAt()),
                tombstone.recordVersion(), tombstone.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Worker quarantine request tombstone was not inserted");
        }
    }

    private static void requireDeleted(int deleted, String source) {
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Worker quarantine retention " + source + " fence was rejected");
        }
    }

    private Optional<StoredRequestTombstone> findRequestTombstone(
            RequestKind kind,
            WorkerAcquisitionScope scope,
            String requestId,
            boolean forUpdate) {
        String safeScopeKey = scopeKey(scope);
        String legacyRequestKey = legacyRequestTombstoneKey(kind, safeScopeKey, requestId);
        List<WorkerQuarantineRequestKeyProtector.IndexKey> candidates =
                requestKeyProtector.lookupCandidates(kind.name(), safeScopeKey, requestId);
        List<String> requestKeys = new ArrayList<>(
                candidates.stream().map(WorkerQuarantineRequestKeyProtector.IndexKey::value)
                        .distinct().toList());
        if (!requestKeys.contains(legacyRequestKey)) {
            requestKeys.add(legacyRequestKey);
        }
        String placeholders = String.join(", ", requestKeys.stream()
                .map(ignored -> "?").toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(kind.name());
        parameters.add(safeScopeKey);
        parameters.addAll(requestKeys);
        String query = """
                SELECT request_kind, scope_key, request_key_id, request_key,
                       request_fingerprint,
                       source_record_fingerprint, source_completed_at, tombstoned_at,
                       expires_at, record_version, record_fingerprint
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = ? AND scope_key = ? AND request_key IN (%s)
                """.formatted(placeholders) + (forUpdate ? " FOR UPDATE" : "");
        List<StoredRequestTombstone> rows = jdbc.query(query, this::mapRequestTombstone,
                parameters.toArray());
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Worker quarantine request tombstone is not unique");
        }
        rows.forEach(this::requireValid);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredRequestTombstone found = rows.getFirst();
        boolean indexMatches = found.recordVersion() == 1
                ? found.requestKey().equals(legacyRequestKey)
                : requestKeyProtector.matches(kind.name(), safeScopeKey, requestId,
                found.requestKeyId(), found.requestKey());
        if (!indexMatches) {
            throw new IllegalStateException(
                    "Stored worker quarantine request tombstone index is inconsistent");
        }
        if (forUpdate && (found.recordVersion() == 1
                || requestKeyProtector.requiresRekey(found.requestKeyId()))) {
            found = rekeyRequestTombstone(found, requestId);
        }
        return Optional.of(found);
    }

    private StoredRequestTombstone rekeyRequestTombstone(
            StoredRequestTombstone current, String requestId) {
        WorkerQuarantineRequestKeyProtector.IndexKey index = requestKeyProtector.protect(
                current.kind().name(), current.scopeKey(), requestId);
        StoredRequestTombstone migrated = new StoredRequestTombstone(
                current.kind(), current.scopeKey(), index.keyId(), index.value(),
                current.requestFingerprint(), current.sourceRecordFingerprint(),
                current.sourceCompletedAt(), current.tombstonedAt(), current.expiresAt(),
                TOMBSTONE_RECORD_VERSION, "");
        migrated = migrated.withFingerprint(requestTombstoneFingerprint(migrated));
        int changed = jdbc.update("""
                UPDATE rg_test_durable_worker_quarantine_request_tombstones
                SET request_key_id = ?, request_key = ?, record_version = ?,
                    record_fingerprint = ?
                WHERE request_kind = ? AND scope_key = ? AND request_key_id = ?
                  AND request_key = ? AND record_version = ? AND record_fingerprint = ?
                """, migrated.requestKeyId(), migrated.requestKey(), migrated.recordVersion(),
                migrated.recordFingerprint(), current.kind().name(), current.scopeKey(),
                current.requestKeyId(), current.requestKey(), current.recordVersion(),
                current.recordFingerprint());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Worker quarantine request tombstone re-key fence was rejected");
        }
        return migrated;
    }

    private StoredRequestTombstone mapRequestTombstone(
            ResultSet rs, int rowNumber) throws SQLException {
        RequestKind kind;
        try {
            kind = RequestKind.valueOf(rs.getString("request_kind"));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Stored worker quarantine request tombstone is corrupt", invalid);
        }
        return new StoredRequestTombstone(kind, rs.getString("scope_key"),
                rs.getString("request_key_id"), rs.getString("request_key"),
                rs.getString("request_fingerprint"),
                rs.getString("source_record_fingerprint"),
                rs.getTimestamp("source_completed_at").toInstant(),
                rs.getTimestamp("tombstoned_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("record_version"),
                rs.getString("record_fingerprint"));
    }

    private void requireValid(StoredRequestTombstone tombstone) {
        boolean valid;
        try {
            boolean legacy = tombstone.recordVersion() == 1;
            boolean current = tombstone.recordVersion() == TOMBSTONE_RECORD_VERSION;
            boolean legacyIndex = tombstone.requestKeyId().isBlank()
                    && tombstone.requestKey().length() == 71
                    && tombstone.requestKey().startsWith("sha256:");
            boolean currentIndex = !tombstone.requestKeyId().isBlank();
            if (currentIndex) {
                new WorkerQuarantineRequestKeyProtector.IndexKey(
                        tombstone.requestKeyId(), tombstone.requestKey());
            }
            valid = (legacy && legacyIndex || current && currentIndex)
                    && tombstone.sourceCompletedAt().isAfter(Instant.EPOCH)
                    && !tombstone.tombstonedAt().isBefore(tombstone.sourceCompletedAt())
                    && tombstone.expiresAt().isAfter(tombstone.tombstonedAt())
                    && requestTombstoneFingerprint(tombstone).equals(
                    tombstone.recordFingerprint());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Stored worker quarantine request tombstone is corrupt", invalid);
        }
        if (!valid) {
            throw new IllegalStateException(
                    "Stored worker quarantine request tombstone is corrupt");
        }
    }

    private String requestTombstoneFingerprint(StoredRequestTombstone tombstone) {
        if (tombstone.recordVersion() == 1) {
            return legacyRequestTombstoneFingerprint(tombstone);
        }
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRequestTombstone.v2"),
                Map.entry("requestKind", tombstone.kind().name()),
                Map.entry("scopeKey", tombstone.scopeKey()),
                Map.entry("requestKeyId", tombstone.requestKeyId()),
                Map.entry("requestKey", tombstone.requestKey()),
                Map.entry("requestFingerprint", tombstone.requestFingerprint()),
                Map.entry("sourceRecordFingerprint", tombstone.sourceRecordFingerprint()),
                Map.entry("sourceCompletedAt", tombstone.sourceCompletedAt()),
                Map.entry("tombstonedAt", tombstone.tombstonedAt()),
                Map.entry("expiresAt", tombstone.expiresAt()),
                Map.entry("recordVersion", tombstone.recordVersion())));
    }

    private String legacyRequestTombstoneFingerprint(StoredRequestTombstone tombstone) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRequestTombstone.v1"),
                Map.entry("requestKind", tombstone.kind().name()),
                Map.entry("scopeKey", tombstone.scopeKey()),
                Map.entry("requestKey", tombstone.requestKey()),
                Map.entry("requestFingerprint", tombstone.requestFingerprint()),
                Map.entry("sourceRecordFingerprint", tombstone.sourceRecordFingerprint()),
                Map.entry("sourceCompletedAt", tombstone.sourceCompletedAt()),
                Map.entry("tombstonedAt", tombstone.tombstonedAt()),
                Map.entry("expiresAt", tombstone.expiresAt())));
    }

    private String legacyRequestTombstoneKey(
            RequestKind kind, String scopeKey, String requestId) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRequestKey.v1"),
                Map.entry("requestKind", kind.name()),
                Map.entry("scopeKey", scopeKey),
                Map.entry("clientRequestId", requestId)));
    }

    private void validateRequestTombstoneKeyRing() {
        List<TombstoneKeyGeneration> generations = jdbc.query("""
                        SELECT DISTINCT record_version, request_key_id
                        FROM rg_test_durable_worker_quarantine_request_tombstones
                        WHERE expires_at > CURRENT_TIMESTAMP
                        """, (rs, rowNumber) -> new TombstoneKeyGeneration(
                        rs.getInt("record_version"), rs.getString("request_key_id")));
        for (TombstoneKeyGeneration generation : generations) {
            boolean legacy = generation.recordVersion() == 1
                    && generation.keyId().isBlank();
            boolean current = generation.recordVersion() == TOMBSTONE_RECORD_VERSION
                    && !generation.keyId().isBlank()
                    && requestKeyProtector.containsKey(generation.keyId());
            if (!legacy && !current) {
                throw new IllegalStateException(
                        "Worker quarantine request tombstone key generation is unavailable");
            }
        }
    }

    /**
     * Lists one bounded, payload-free quarantine page in an exact verified worker scope.
     *
     * <p>An expired claim is projected as {@link QuarantineState#AVAILABLE}; list operations do
     * not mutate durable ownership. The next claim command advances the persisted version while
     * consuming the expired fence.</p>
     *
     * @param scope tenant, organization, project, and non-production environment authority
     * @param actionableOnly whether to omit quarantines with a live maintenance owner
     * @param limit page size from 1 through 1,000
     * @return oldest quarantines first
     */
    public List<QuarantineRecord> quarantines(
            WorkerAcquisitionScope scope, boolean actionableOnly, int limit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        int safeLimit = bounded(limit);
        List<QuarantineRecord> result = observations.execute(status -> {
            Instant observedAt = databaseNow();
            String actionablePredicate = actionableOnly
                    ? """
                       AND (c.scope_key IS NULL OR c.control_state = 'AVAILABLE'
                            OR (c.control_state = 'CLAIMED' AND c.claim_until <= ?))
                       """ : "";
            String sql = quarantineSelect() + """
                    WHERE q.scope_key = ? AND q.tenant_id = ? AND q.environment_id = ?
                      AND q.organization_id = ? AND q.project_id = ?
                    """ + actionablePredicate + """
                    ORDER BY q.quarantined_at, q.run_id
                    LIMIT ?
                    """;
            Object[] arguments = actionableOnly
                    ? new Object[]{scopeKey(safeScope), safeScope.tenantId(),
                    safeScope.environmentId(), safeScope.organizationId(), safeScope.projectId(),
                    Timestamp.from(observedAt), safeLimit}
                    : new Object[]{scopeKey(safeScope), safeScope.tenantId(),
                    safeScope.environmentId(), safeScope.organizationId(), safeScope.projectId(),
                    safeLimit};
            return jdbc.query(sql, (rs, rowNumber) -> mapQuarantine(rs, observedAt), arguments);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine observation returned no result");
        }
        return List.copyOf(result);
    }

    /**
     * Idempotently claims one exact-checkpoint quarantine for a verified maintenance actor.
     *
     * <p>The checkpoint authority is locked and integrity-verified before the quarantine and its
     * control row. A fresh claim, its immutable command receipt, and the supplied audit mutation
     * therefore commit or roll back together. Exact retries return the original server token;
     * request-ID drift never reaches the active record.</p>
     *
     * @param scope verified tenant, organization, project, and environment authority
     * @param key exact run and checkpoint identity from the payload-free list
     * @param ownerId verified workload actor, never caller-selected request data
     * @param clientRequestId caller-stable idempotency key
     * @param claimDuration database-clock lease from one second through one hour
     * @param committedAudit transaction-bound audit mutation for a fresh claim
     * @return claimed, replayed, conflicting, stale, or non-actionable disposition
     */
    public QuarantineClaimResult claim(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String ownerId,
            String clientRequestId,
            Duration claimDuration,
            Function<QuarantineClaim, TestRuntimeTransactionMutation> committedAudit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        QuarantineKey safeKey = Objects.requireNonNull(key, "key");
        String safeOwner = required(ownerId, "ownerId", 255);
        String safeRequestId = required(clientRequestId, "clientRequestId", 255);
        Duration safeDuration = boundedLease(claimDuration);
        Function<QuarantineClaim, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimIntent.v1"),
                Map.entry("scope", safeScope), Map.entry("key", safeKey),
                Map.entry("ownerId", safeOwner),
                Map.entry("claimDurationSeconds", safeDuration.toSeconds())));
        QuarantineClaimResult result = transactions.execute(status -> {
            Optional<StoredClaimCommand> replay = findClaimCommand(
                    safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredClaimCommand stored = normalizeClaimCommand(replay.orElseThrow());
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineClaimResult.replay(claimFrom(stored))
                        : QuarantineClaimResult.conflict();
            }
            Optional<StoredRequestTombstone> tombstone = findRequestTombstone(
                    RequestKind.CLAIM, safeScope, safeRequestId, true);
            if (tombstone.isPresent()) {
                return tombstone.orElseThrow().requestFingerprint().equals(requestFingerprint)
                        ? QuarantineClaimResult.replayWindowExpired()
                        : QuarantineClaimResult.conflict();
            }
            if (!lockExactCheckpoint(safeScope, safeKey)) {
                return QuarantineClaimResult.staleCheckpoint();
            }
            replay = findClaimCommand(safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredClaimCommand stored = normalizeClaimCommand(replay.orElseThrow());
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineClaimResult.replay(claimFrom(stored))
                        : QuarantineClaimResult.conflict();
            }
            StoredQuarantine quarantine = findQuarantine(
                    safeScope, safeKey, true).orElse(null);
            if (quarantine == null) {
                return QuarantineClaimResult.notActionable();
            }
            requireValid(quarantine);
            Instant now = databaseNow();
            StoredControl current = findControl(safeScope, safeKey.runId(), true).orElse(null);
            if (current != null) {
                requireValid(current, quarantine);
                if (current.state() == QuarantineState.CLAIMED
                        && current.claimUntil().isAfter(now)) {
                    return QuarantineClaimResult.notActionable();
                }
            }
            long nextVersion = Math.addExact(current == null ? 0 : current.version(), 1);
            String token = UUID.randomUUID().toString();
            Instant claimUntil = now.plus(safeDuration);
            StoredControl claimed = storedControl(quarantine, QuarantineState.CLAIMED,
                    safeOwner, token, claimUntil, nextVersion);
            persistControl(current, claimed);
            QuarantineClaim claim = new QuarantineClaim(
                    safeKey, safeOwner, token, nextVersion, claimUntil);
            StoredClaimCommand command = storedClaimCommand(
                    safeScope, safeRequestId, safeKey, safeOwner, safeDuration,
                    requestFingerprint, claim, now);
            insertClaimCommand(command);
            Objects.requireNonNull(safeAudit.apply(claim), "committedAudit result").apply(jdbc);
            return QuarantineClaimResult.claimed(claim);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine claim returned no result");
        }
        return result;
    }

    /**
     * Resolves one exact live maintenance claim with an immutable token-free receipt.
     *
     * <p>{@link ResolutionAction#RELEASE} returns the quarantine to the actionable maintenance
     * queue while preserving worker suppression. {@link ResolutionAction#DISCARD} removes only
     * the exact checkpoint quarantine and is therefore the explicit operator override that makes
     * the unchanged checkpoint eligible again. The checkpoint, quarantine, claim, command
     * receipt, history row, and audit mutation share one database transaction.</p>
     *
     * @param scope verified worker scope
     * @param claim exact server-issued owner, token, version, and expiry fence
     * @param clientRequestId caller-stable idempotency key
     * @param action release ownership or discard the exact quarantine
     * @param reasonCode bounded non-payload operational rationale
     * @param committedAudit transaction-bound audit mutation for a fresh resolution
     * @return resolved, replayed, conflicting, stale, or fence-rejected result
     */
    public QuarantineResolutionResult resolve(
            WorkerAcquisitionScope scope,
            QuarantineClaim claim,
            String clientRequestId,
            ResolutionAction action,
            String reasonCode,
            Function<QuarantineResolutionReceipt, TestRuntimeTransactionMutation>
                    committedAudit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        QuarantineClaim safeClaim = Objects.requireNonNull(claim, "claim");
        String safeRequestId = required(clientRequestId, "clientRequestId", 255);
        ResolutionAction safeAction = Objects.requireNonNull(action, "action");
        String safeReason = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
        if (!REASON_CODE.matcher(safeReason).matches()) {
            throw new IllegalArgumentException("Worker quarantine reasonCode is invalid");
        }
        Function<QuarantineResolutionReceipt, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        String requestFingerprint = resolutionRequestFingerprint(
                safeScope, safeClaim, safeAction, safeReason);
        QuarantineResolutionResult result = transactions.execute(status -> {
            Optional<StoredResolutionCommand> replay = findResolutionCommand(
                    safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredResolutionCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineResolutionResult.replay(stored.receipt())
                        : QuarantineResolutionResult.conflict();
            }
            Optional<StoredRequestTombstone> tombstone = findRequestTombstone(
                    RequestKind.RESOLUTION, safeScope, safeRequestId, true);
            if (tombstone.isPresent()) {
                return tombstone.orElseThrow().requestFingerprint().equals(requestFingerprint)
                        ? QuarantineResolutionResult.replayWindowExpired()
                        : QuarantineResolutionResult.conflict();
            }
            if (!lockExactCheckpoint(safeScope, safeClaim.key())) {
                return QuarantineResolutionResult.staleCheckpoint();
            }
            replay = findResolutionCommand(safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredResolutionCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineResolutionResult.replay(stored.receipt())
                        : QuarantineResolutionResult.conflict();
            }
            if (safeAction == ResolutionAction.DISCARD) {
                return QuarantineResolutionResult.approvalRequired();
            }
            StoredQuarantine quarantine = findQuarantine(
                    safeScope, safeClaim.key(), true).orElse(null);
            if (quarantine == null) {
                return QuarantineResolutionResult.fenceRejected();
            }
            requireValid(quarantine);
            StoredControl control = findControl(
                    safeScope, safeClaim.key().runId(), true).orElse(null);
            Instant now = databaseNow();
            if (control == null) {
                return QuarantineResolutionResult.fenceRejected();
            }
            requireValid(control, quarantine);
            if (!matchesActiveClaim(control, safeClaim, now)) {
                return QuarantineResolutionResult.fenceRejected();
            }
            long nextVersion = Math.addExact(control.version(), 1);
            QuarantineResolutionReceipt receipt = resolutionReceipt(
                    safeScope, safeClaim.key(), safeClaim.ownerId(), safeAction, safeReason,
                    nextVersion, now);
            StoredResolutionCommand command = storedResolutionCommand(
                    safeScope, safeRequestId, safeClaim.key(), safeClaim.ownerId(), safeAction,
                    safeReason, requestFingerprint, receipt);
            StoredHistory history = storedHistory(safeScope, quarantine, receipt);
            if (safeAction == ResolutionAction.RELEASE) {
                persistControl(control, storedControl(quarantine, QuarantineState.AVAILABLE,
                        "", "", Instant.EPOCH, nextVersion));
            } else {
                deleteQuarantine(quarantine);
            }
            insertResolutionCommand(command);
            insertHistory(history);
            Objects.requireNonNull(safeAudit.apply(receipt), "committedAudit result").apply(jdbc);
            return QuarantineResolutionResult.resolved(receipt);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine resolution returned no result");
        }
        return result;
    }

    /**
     * Lists immutable, token-free manual action history in one verified worker scope.
     *
     * @param scope exact tenant, organization, project, and environment authority
     * @param limit page size from 1 through 1,000
     * @return newest action receipts first
     */
    public List<QuarantineHistoryRecord> history(
            WorkerAcquisitionScope scope, int limit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        List<QuarantineHistoryRecord> result = observations.execute(status -> jdbc.query("""
                        SELECT history_id, scope_key, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               quarantine_reason, consecutive_failures, quarantine_threshold,
                               first_observed_at, quarantined_at, resolution_action, reason_code,
                               resolution_owner, result_version, acted_at, receipt_fingerprint,
                               record_fingerprint
                        FROM rg_test_durable_worker_quarantine_history
                        WHERE scope_key = ? AND tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                        ORDER BY acted_at DESC, history_id
                        LIMIT ?
                        """, this::mapHistory, scopeKey(safeScope), safeScope.tenantId(),
                safeScope.environmentId(), safeScope.organizationId(), safeScope.projectId(),
                bounded(limit)));
        if (result == null) {
            throw new IllegalStateException("Worker quarantine history returned no result");
        }
        result.forEach(this::requireValidHistoryRecord);
        return List.copyOf(result);
    }

    /**
     * Idempotently approves one exact live claim for a later two-person discard.
     *
     * <p>The approver never receives or supplies the claim token. Instead, the approval binds the
     * database-authoritative claim owner, version, and expiry observed in the payload-free queue.
     * The checkpoint and maintenance rows are locked before those values are accepted. Self
     * approval is rejected before mutation, and the resulting approval expires no later than the
     * claim it authorizes.</p>
     *
     * @param scope verified tenant, organization, project, and environment authority
     * @param key exact run and checkpoint identity
     * @param claimOwner maker identity projected by the active claim
     * @param claimVersion exact maintenance generation projected by the active claim
     * @param claimUntil exact database-clock claim deadline
     * @param approverId verified checker identity, which must differ from the maker
     * @param clientRequestId caller-stable approval idempotency key
     * @param reasonCode exact non-payload rationale later required by discard
     * @param approvalDuration bounded database-clock approval lifetime
     * @param committedAudit transaction-bound checker audit mutation
     * @return approved, replayed, conflicting, stale, rejected, or self-approval disposition
     */
    public DiscardApprovalResult approveDiscard(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            String approverId,
            String clientRequestId,
            String reasonCode,
            Duration approvalDuration,
            Function<DiscardApproval, TestRuntimeTransactionMutation> committedAudit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        QuarantineKey safeKey = Objects.requireNonNull(key, "key");
        String safeClaimOwner = required(claimOwner, "claimOwner", 255);
        if (claimVersion <= 0) {
            throw new IllegalArgumentException("claimVersion must be positive");
        }
        Instant safeClaimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
        String safeApprover = required(approverId, "approverId", 255);
        String safeRequestId = required(clientRequestId, "clientRequestId", 255);
        String safeReason = reason(reasonCode);
        Duration safeDuration = boundedApproval(approvalDuration);
        Function<DiscardApproval, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        if (safeApprover.equals(safeClaimOwner)) {
            return DiscardApprovalResult.selfApproval();
        }
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineDiscardApprovalIntent.v1"),
                Map.entry("scope", safeScope), Map.entry("key", safeKey),
                Map.entry("claimOwner", safeClaimOwner),
                Map.entry("claimVersion", claimVersion),
                Map.entry("claimUntil", safeClaimUntil),
                Map.entry("approverId", safeApprover),
                Map.entry("reasonCode", safeReason),
                Map.entry("approvalDurationSeconds", safeDuration.toSeconds())));
        DiscardApprovalResult result = transactions.execute(status -> {
            Optional<StoredDiscardApproval> replay = findDiscardApprovalCommand(
                    safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredDiscardApproval stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? DiscardApprovalResult.replay(stored.external())
                        : DiscardApprovalResult.conflict();
            }
            Optional<StoredRequestTombstone> tombstone = findRequestTombstone(
                    RequestKind.DISCARD_APPROVAL, safeScope, safeRequestId, true);
            if (tombstone.isPresent()) {
                return tombstone.orElseThrow().requestFingerprint().equals(requestFingerprint)
                        ? DiscardApprovalResult.replayWindowExpired()
                        : DiscardApprovalResult.conflict();
            }
            if (!lockExactCheckpoint(safeScope, safeKey)) {
                return DiscardApprovalResult.staleCheckpoint();
            }
            replay = findDiscardApprovalCommand(safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredDiscardApproval stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? DiscardApprovalResult.replay(stored.external())
                        : DiscardApprovalResult.conflict();
            }
            StoredQuarantine quarantine = findQuarantine(safeScope, safeKey, true).orElse(null);
            if (quarantine == null) {
                return DiscardApprovalResult.fenceRejected();
            }
            requireValid(quarantine);
            StoredControl control = findControl(safeScope, safeKey.runId(), true).orElse(null);
            Instant now = databaseNow();
            if (control == null) {
                return DiscardApprovalResult.fenceRejected();
            }
            requireValid(control, quarantine);
            if (control.state() != QuarantineState.CLAIMED
                    || !control.claimOwner().equals(safeClaimOwner)
                    || control.version() != claimVersion
                    || !control.claimUntil().equals(safeClaimUntil)
                    || !control.claimUntil().isAfter(now)) {
                return DiscardApprovalResult.fenceRejected();
            }
            Instant approvalUntil = earlierOf(now.plus(safeDuration), safeClaimUntil);
            if (!approvalUntil.isAfter(now)) {
                return DiscardApprovalResult.fenceRejected();
            }
            StoredDiscardApproval stored = storedDiscardApproval(
                    safeScope, safeRequestId, safeKey, safeClaimOwner, claimVersion,
                    safeClaimUntil, safeApprover, safeReason, now, approvalUntil,
                    requestFingerprint);
            insertDiscardApproval(stored);
            DiscardApproval approval = stored.external();
            Objects.requireNonNull(safeAudit.apply(approval), "committedAudit result").apply(jdbc);
            return DiscardApprovalResult.approved(approval);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine discard approval returned no result");
        }
        return result;
    }

    /**
     * Discards one exact quarantine after atomically consuming an independent approval.
     *
     * <p>The maker must still prove the live secret claim fence. The checker approval is locked,
     * integrity-verified, matched to the same claim closure and rationale, required to be live,
     * and consumed in the transaction that deletes the quarantine. The approval, discard command,
     * retained two-person history, and audit mutation therefore cannot diverge.</p>
     *
     * @param scope verified worker scope
     * @param claim exact maker claim including its secret token
     * @param approvalId checker approval identity
     * @param clientRequestId caller-stable discard idempotency key
     * @param reasonCode rationale that must exactly equal the approval rationale
     * @param committedAudit transaction-bound maker audit mutation
     * @return discarded, replayed, conflicting, stale, fence-rejected, or approval-rejected result
     */
    public ApprovedDiscardResult discard(
            WorkerAcquisitionScope scope,
            QuarantineClaim claim,
            String approvalId,
            String clientRequestId,
            String reasonCode,
            Function<ApprovedDiscardReceipt, TestRuntimeTransactionMutation> committedAudit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        QuarantineClaim safeClaim = Objects.requireNonNull(claim, "claim");
        String safeApprovalId = required(approvalId, "approvalId", 36);
        String safeRequestId = required(clientRequestId, "clientRequestId", 255);
        String safeReason = reason(reasonCode);
        Function<ApprovedDiscardReceipt, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineApprovedDiscardIntent.v1"),
                Map.entry("scope", safeScope), Map.entry("key", safeClaim.key()),
                Map.entry("ownerId", safeClaim.ownerId()),
                Map.entry("claimToken", safeClaim.claimToken()),
                Map.entry("claimVersion", safeClaim.version()),
                Map.entry("claimUntil", safeClaim.claimUntil()),
                Map.entry("approvalId", safeApprovalId),
                Map.entry("reasonCode", safeReason)));
        ApprovedDiscardResult result = transactions.execute(status -> {
            Optional<StoredApprovedDiscardCommand> replay = findApprovedDiscardCommand(
                    safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredApprovedDiscardCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? ApprovedDiscardResult.replay(stored.receipt())
                        : ApprovedDiscardResult.conflict();
            }
            Optional<StoredRequestTombstone> tombstone = findRequestTombstone(
                    RequestKind.APPROVED_DISCARD, safeScope, safeRequestId, true);
            if (tombstone.isPresent()) {
                return tombstone.orElseThrow().requestFingerprint().equals(requestFingerprint)
                        ? ApprovedDiscardResult.replayWindowExpired()
                        : ApprovedDiscardResult.conflict();
            }
            if (!lockExactCheckpoint(safeScope, safeClaim.key())) {
                return ApprovedDiscardResult.staleCheckpoint();
            }
            replay = findApprovedDiscardCommand(safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredApprovedDiscardCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? ApprovedDiscardResult.replay(stored.receipt())
                        : ApprovedDiscardResult.conflict();
            }
            StoredQuarantine quarantine = findQuarantine(
                    safeScope, safeClaim.key(), true).orElse(null);
            if (quarantine == null) {
                return ApprovedDiscardResult.fenceRejected();
            }
            requireValid(quarantine);
            StoredControl control = findControl(
                    safeScope, safeClaim.key().runId(), true).orElse(null);
            Instant now = databaseNow();
            if (control == null) {
                return ApprovedDiscardResult.fenceRejected();
            }
            requireValid(control, quarantine);
            if (!matchesActiveClaim(control, safeClaim, now)) {
                return ApprovedDiscardResult.fenceRejected();
            }
            StoredDiscardApproval approval = findDiscardApprovalById(
                    safeScope, safeApprovalId, true).orElse(null);
            if (approval == null) {
                return ApprovedDiscardResult.approvalRejected();
            }
            requireValid(approval);
            if (approval.state() != DiscardApprovalState.APPROVED
                    || !approval.key().equals(safeClaim.key())
                    || !approval.claimOwner().equals(safeClaim.ownerId())
                    || approval.claimVersion() != safeClaim.version()
                    || !approval.claimUntil().equals(safeClaim.claimUntil())
                    || !approval.reasonCode().equals(safeReason)
                    || approval.approverId().equals(safeClaim.ownerId())
                    || !approval.approvalUntil().isAfter(now)) {
                return ApprovedDiscardResult.approvalRejected();
            }
            long nextVersion = Math.addExact(control.version(), 1);
            ApprovedDiscardReceipt receipt = approvedDiscardReceipt(
                    safeScope, safeClaim.key(), safeClaim.ownerId(), approval,
                    safeReason, nextVersion, now);
            StoredApprovedDiscardCommand command = storedApprovedDiscardCommand(
                    safeScope, safeRequestId, safeClaim, approval, safeReason,
                    requestFingerprint, receipt);
            StoredApprovedDiscardHistory history = storedApprovedDiscardHistory(
                    safeScope, quarantine, receipt);
            deleteQuarantine(quarantine);
            consumeDiscardApproval(approval, safeRequestId, now);
            insertApprovedDiscardCommand(command);
            insertApprovedDiscardHistory(history);
            Objects.requireNonNull(safeAudit.apply(receipt), "committedAudit result").apply(jdbc);
            return ApprovedDiscardResult.discarded(receipt);
        });
        if (result == null) {
            throw new IllegalStateException("Approved worker quarantine discard returned no result");
        }
        return result;
    }

    /**
     * Lists bounded, token-free two-person discard evidence in one verified worker scope.
     *
     * @param scope exact tenant, organization, project, and environment authority
     * @param limit page size from 1 through 1,000
     * @return newest approved discard evidence first
     */
    public List<ApprovedDiscardHistoryRecord> discardHistory(
            WorkerAcquisitionScope scope, int limit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        List<ApprovedDiscardHistoryRecord> result = observations.execute(status -> jdbc.query("""
                        SELECT history_id, scope_key, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               quarantine_reason, consecutive_failures, quarantine_threshold,
                               first_observed_at, quarantined_at, reason_code, resolution_owner,
                               approval_id, approver_id, approval_fingerprint, result_version,
                               acted_at, receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_discard_history
                        WHERE scope_key = ? AND tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                        ORDER BY acted_at DESC, history_id
                        LIMIT ?
                        """, this::mapApprovedDiscardHistory, scopeKey(safeScope),
                safeScope.tenantId(), safeScope.environmentId(), safeScope.organizationId(),
                safeScope.projectId(), bounded(limit)));
        if (result == null) {
            throw new IllegalStateException("Approved worker quarantine history returned no result");
        }
        result.forEach(Objects::requireNonNull);
        return List.copyOf(result);
    }

    private Optional<StoredDiscardApproval> findDiscardApprovalCommand(
            WorkerAcquisitionScope scope, String requestId, boolean forUpdate) {
        List<StoredDiscardApproval> rows = jdbc.query(discardApprovalSelect() + """
                        WHERE scope_key = ? AND client_request_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                this::mapDiscardApproval, scopeKey(scope), requestId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine discard approval is not unique");
        }
        return rows.stream().findFirst();
    }

    private Optional<StoredDiscardApproval> findDiscardApprovalById(
            WorkerAcquisitionScope scope, String approvalId, boolean forUpdate) {
        List<StoredDiscardApproval> rows = jdbc.query(discardApprovalSelect() + """
                        WHERE scope_key = ? AND approval_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                this::mapDiscardApproval, scopeKey(scope), approvalId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine approval identity is not unique");
        }
        return rows.stream().findFirst();
    }

    private String discardApprovalSelect() {
        return """
                SELECT scope_key, client_request_id, approval_id, tenant_id, environment_id,
                       organization_id, project_id, run_id, checkpoint_fingerprint,
                       claim_owner, claim_version, claim_until, approver_id, reason_code,
                       approved_at, approval_until, approval_state, consumed_by_request_id,
                       consumed_at, request_fingerprint, approval_fingerprint,
                       record_fingerprint
                FROM rg_test_durable_worker_quarantine_discard_approvals
                """;
    }

    private StoredDiscardApproval mapDiscardApproval(
            ResultSet rs, int rowNumber) throws SQLException {
        return new StoredDiscardApproval(rs.getString("scope_key"),
                rs.getString("client_request_id"), rs.getString("approval_id"),
                rs.getString("tenant_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("environment_id"),
                rs.getString("run_id"), rs.getString("checkpoint_fingerprint"),
                rs.getString("claim_owner"), rs.getLong("claim_version"),
                rs.getTimestamp("claim_until").toInstant(), rs.getString("approver_id"),
                rs.getString("reason_code"), rs.getTimestamp("approved_at").toInstant(),
                rs.getTimestamp("approval_until").toInstant(),
                rs.getString("approval_state"), rs.getString("consumed_by_request_id"),
                rs.getTimestamp("consumed_at").toInstant(),
                rs.getString("request_fingerprint"), rs.getString("approval_fingerprint"),
                rs.getString("record_fingerprint"));
    }

    private StoredDiscardApproval storedDiscardApproval(
            WorkerAcquisitionScope scope,
            String requestId,
            QuarantineKey key,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            String approverId,
            String reasonCode,
            Instant approvedAt,
            Instant approvalUntil,
            String requestFingerprint) {
        StoredDiscardApproval unsealed = new StoredDiscardApproval(scopeKey(scope), requestId,
                UUID.randomUUID().toString(), scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), key.runId(),
                key.checkpointFingerprint(), claimOwner, claimVersion, claimUntil, approverId,
                reasonCode, approvedAt, approvalUntil, DiscardApprovalState.APPROVED.name(),
                "", Instant.EPOCH, requestFingerprint, "", "");
        StoredDiscardApproval approved = unsealed.withApprovalFingerprint(
                discardApprovalFingerprint(unsealed));
        return approved.withRecordFingerprint(discardApprovalRecordFingerprint(approved));
    }

    private void insertDiscardApproval(StoredDiscardApproval approval) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_discard_approvals (
                    scope_key, client_request_id, approval_id, tenant_id, environment_id,
                    organization_id, project_id, run_id, checkpoint_fingerprint,
                    claim_owner, claim_version, claim_until, approver_id, reason_code,
                    approved_at, approval_until, approval_state, consumed_by_request_id,
                    consumed_at, request_fingerprint, approval_fingerprint, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, approval.scopeKey(), approval.clientRequestId(), approval.approvalId(),
                approval.tenantId(), approval.environmentId(), approval.organizationId(),
                approval.projectId(), approval.runId(), approval.checkpointFingerprint(),
                approval.claimOwner(), approval.claimVersion(),
                Timestamp.from(approval.claimUntil()), approval.approverId(),
                approval.reasonCode(), Timestamp.from(approval.approvedAt()),
                Timestamp.from(approval.approvalUntil()), approval.state().name(),
                approval.consumedByRequestId(), Timestamp.from(approval.consumedAt()),
                approval.requestFingerprint(), approval.approvalFingerprint(),
                approval.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Worker quarantine discard approval was not inserted");
        }
    }

    private void consumeDiscardApproval(
            StoredDiscardApproval current, String discardRequestId, Instant consumedAt) {
        StoredDiscardApproval consumed = current.consumed(discardRequestId, consumedAt);
        consumed = consumed.withRecordFingerprint(discardApprovalRecordFingerprint(consumed));
        int changed = jdbc.update("""
                UPDATE rg_test_durable_worker_quarantine_discard_approvals
                SET approval_state = ?, consumed_by_request_id = ?, consumed_at = ?,
                    record_fingerprint = ?
                WHERE scope_key = ? AND client_request_id = ? AND approval_id = ?
                  AND approval_state = ? AND record_fingerprint = ?
                """, consumed.state().name(), consumed.consumedByRequestId(),
                Timestamp.from(consumed.consumedAt()), consumed.recordFingerprint(),
                current.scopeKey(), current.clientRequestId(), current.approvalId(),
                DiscardApprovalState.APPROVED.name(), current.recordFingerprint());
        if (changed != 1) {
            throw new IllegalStateException("Worker quarantine discard approval fence was rejected");
        }
    }

    private void requireValid(StoredDiscardApproval approval) {
        WorkerAcquisitionScope scope;
        DiscardApproval external;
        try {
            scope = approval.scope();
            external = approval.external();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine discard approval is corrupt",
                    invalid);
        }
        boolean stateShape = approval.state() == DiscardApprovalState.APPROVED
                ? approval.consumedByRequestId().isBlank()
                && approval.consumedAt().equals(Instant.EPOCH)
                : !approval.consumedByRequestId().isBlank()
                && !approval.consumedAt().isBefore(approval.approvedAt());
        if (!scopeKey(scope).equals(approval.scopeKey())
                || external.claimOwner().equals(external.approverId())
                || external.approvedAt().isAfter(external.approvalUntil())
                || external.approvalUntil().isAfter(external.claimUntil())
                || !stateShape
                || !discardApprovalFingerprint(approval).equals(
                        approval.approvalFingerprint())
                || !discardApprovalRecordFingerprint(approval).equals(
                        approval.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine discard approval is corrupt");
        }
    }

    private String discardApprovalFingerprint(StoredDiscardApproval approval) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineDiscardApproval.v1"),
                Map.entry("approvalId", approval.approvalId()),
                Map.entry("scope", approval.scope()), Map.entry("key", approval.key()),
                Map.entry("claimOwner", approval.claimOwner()),
                Map.entry("claimVersion", approval.claimVersion()),
                Map.entry("claimUntil", approval.claimUntil()),
                Map.entry("approverId", approval.approverId()),
                Map.entry("reasonCode", approval.reasonCode()),
                Map.entry("approvedAt", approval.approvedAt()),
                Map.entry("approvalUntil", approval.approvalUntil())));
    }

    private String discardApprovalRecordFingerprint(StoredDiscardApproval approval) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineDiscardApprovalRecord.v1"),
                Map.entry("clientRequestId", approval.clientRequestId()),
                Map.entry("approvalFingerprint", approval.approvalFingerprint()),
                Map.entry("state", approval.state().name()),
                Map.entry("consumedByRequestId", approval.consumedByRequestId()),
                Map.entry("consumedAt", approval.consumedAt()),
                Map.entry("requestFingerprint", approval.requestFingerprint())));
    }

    private Optional<StoredApprovedDiscardCommand> findApprovedDiscardCommand(
            WorkerAcquisitionScope scope, String requestId, boolean forUpdate) {
        List<StoredApprovedDiscardCommand> rows = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               resolution_owner, claim_version, claim_until, approval_id,
                               approver_id, reason_code, request_fingerprint, result_version,
                               result_acted_at, result_approval_fingerprint,
                               result_receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_discards
                        WHERE scope_key = ? AND client_request_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                this::mapApprovedDiscardCommand, scopeKey(scope), requestId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Approved worker quarantine discard is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredApprovedDiscardCommand mapApprovedDiscardCommand(
            ResultSet rs, int rowNumber) throws SQLException {
        return new StoredApprovedDiscardCommand(rs.getString("scope_key"),
                rs.getString("client_request_id"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("resolution_owner"),
                rs.getLong("claim_version"), rs.getTimestamp("claim_until").toInstant(),
                rs.getString("approval_id"), rs.getString("approver_id"),
                rs.getString("reason_code"), rs.getString("request_fingerprint"),
                rs.getLong("result_version"), rs.getTimestamp("result_acted_at").toInstant(),
                rs.getString("result_approval_fingerprint"),
                rs.getString("result_receipt_fingerprint"),
                rs.getString("record_fingerprint"));
    }

    private StoredApprovedDiscardCommand storedApprovedDiscardCommand(
            WorkerAcquisitionScope scope,
            String requestId,
            QuarantineClaim claim,
            StoredDiscardApproval approval,
            String reasonCode,
            String requestFingerprint,
            ApprovedDiscardReceipt receipt) {
        StoredApprovedDiscardCommand unsealed = new StoredApprovedDiscardCommand(
                scopeKey(scope), requestId, scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), claim.key().runId(),
                claim.key().checkpointFingerprint(), claim.ownerId(), claim.version(),
                claim.claimUntil(), approval.approvalId(), approval.approverId(), reasonCode,
                requestFingerprint, receipt.version(), receipt.actedAt(),
                receipt.approvalFingerprint(), receipt.receiptFingerprint(), "");
        return unsealed.withRecordFingerprint(approvedDiscardCommandFingerprint(unsealed));
    }

    private void insertApprovedDiscardCommand(StoredApprovedDiscardCommand command) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_discards (
                    scope_key, client_request_id, tenant_id, environment_id,
                    organization_id, project_id, run_id, checkpoint_fingerprint,
                    resolution_owner, claim_version, claim_until, approval_id,
                    approver_id, reason_code, request_fingerprint, result_version,
                    result_acted_at, result_approval_fingerprint,
                    result_receipt_fingerprint, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.scopeKey(), command.clientRequestId(), command.tenantId(),
                command.environmentId(), command.organizationId(), command.projectId(),
                command.runId(), command.checkpointFingerprint(), command.resolutionOwner(),
                command.claimVersion(), Timestamp.from(command.claimUntil()),
                command.approvalId(), command.approverId(), command.reasonCode(),
                command.requestFingerprint(), command.resultVersion(),
                Timestamp.from(command.resultActedAt()), command.resultApprovalFingerprint(),
                command.resultReceiptFingerprint(), command.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Approved worker quarantine discard was not inserted");
        }
    }

    private void requireValid(StoredApprovedDiscardCommand command) {
        WorkerAcquisitionScope scope;
        ApprovedDiscardReceipt receipt;
        try {
            scope = command.scope();
            receipt = command.receipt();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored approved worker discard is corrupt", invalid);
        }
        String expectedReceipt = approvedDiscardReceiptFingerprint(scope, receipt.key(),
                receipt.ownerId(), receipt.approvalId(), receipt.approverId(),
                receipt.approvalFingerprint(), receipt.reasonCode(), receipt.version(),
                receipt.actedAt());
        if (!scopeKey(scope).equals(command.scopeKey())
                || command.resolutionOwner().equals(command.approverId())
                || !expectedReceipt.equals(receipt.receiptFingerprint())
                || !approvedDiscardCommandFingerprint(command).equals(
                        command.recordFingerprint())) {
            throw new IllegalStateException("Stored approved worker discard is corrupt");
        }
    }

    private String approvedDiscardCommandFingerprint(StoredApprovedDiscardCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineApprovedDiscardCommand.v1"),
                Map.entry("scope", command.scope()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("key", command.key()),
                Map.entry("ownerId", command.resolutionOwner()),
                Map.entry("claimVersion", command.claimVersion()),
                Map.entry("claimUntil", command.claimUntil()),
                Map.entry("approvalId", command.approvalId()),
                Map.entry("approverId", command.approverId()),
                Map.entry("reasonCode", command.reasonCode()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultActedAt", command.resultActedAt()),
                Map.entry("resultApprovalFingerprint", command.resultApprovalFingerprint()),
                Map.entry("resultReceiptFingerprint", command.resultReceiptFingerprint())));
    }

    private ApprovedDiscardReceipt approvedDiscardReceipt(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String ownerId,
            StoredDiscardApproval approval,
            String reasonCode,
            long version,
            Instant actedAt) {
        String fingerprint = approvedDiscardReceiptFingerprint(scope, key, ownerId,
                approval.approvalId(), approval.approverId(), approval.approvalFingerprint(),
                reasonCode, version, actedAt);
        return new ApprovedDiscardReceipt(key, ownerId, approval.approvalId(),
                approval.approverId(), approval.approvalFingerprint(), reasonCode,
                version, actedAt, fingerprint);
    }

    private String approvedDiscardReceiptFingerprint(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String ownerId,
            String approvalId,
            String approverId,
            String approvalFingerprint,
            String reasonCode,
            long version,
            Instant actedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineApprovedDiscardReceipt.v1"),
                Map.entry("scope", scope), Map.entry("key", key),
                Map.entry("ownerId", ownerId), Map.entry("approvalId", approvalId),
                Map.entry("approverId", approverId),
                Map.entry("approvalFingerprint", approvalFingerprint),
                Map.entry("reasonCode", reasonCode), Map.entry("version", version),
                Map.entry("actedAt", actedAt)));
    }

    private StoredApprovedDiscardHistory storedApprovedDiscardHistory(
            WorkerAcquisitionScope scope,
            StoredQuarantine quarantine,
            ApprovedDiscardReceipt receipt) {
        ActiveWorkerCandidateQuarantine active = quarantine.quarantine();
        StoredApprovedDiscardHistory unsealed = new StoredApprovedDiscardHistory(
                UUID.randomUUID().toString(), scopeKey(scope), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(),
                quarantine.runId(), quarantine.checkpointFingerprint(), active.reason().name(),
                active.consecutiveFailures(), active.quarantineThreshold(),
                active.firstObservedAt(), active.quarantinedAt(), receipt.reasonCode(),
                receipt.ownerId(), receipt.approvalId(), receipt.approverId(),
                receipt.approvalFingerprint(), receipt.version(), receipt.actedAt(),
                receipt.receiptFingerprint(), "");
        return unsealed.withRecordFingerprint(approvedDiscardHistoryFingerprint(unsealed));
    }

    private void insertApprovedDiscardHistory(StoredApprovedDiscardHistory history) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_discard_history (
                    history_id, scope_key, tenant_id, environment_id, organization_id,
                    project_id, run_id, checkpoint_fingerprint, quarantine_reason,
                    consecutive_failures, quarantine_threshold, first_observed_at,
                    quarantined_at, reason_code, resolution_owner, approval_id, approver_id,
                    approval_fingerprint, result_version, acted_at, receipt_fingerprint,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, history.historyId(), history.scopeKey(), history.tenantId(),
                history.environmentId(), history.organizationId(), history.projectId(),
                history.runId(), history.checkpointFingerprint(), history.quarantineReason(),
                history.consecutiveFailures(), history.quarantineThreshold(),
                Timestamp.from(history.firstObservedAt()), Timestamp.from(history.quarantinedAt()),
                history.reasonCode(), history.resolutionOwner(), history.approvalId(),
                history.approverId(), history.approvalFingerprint(), history.resultVersion(),
                Timestamp.from(history.actedAt()), history.receiptFingerprint(),
                history.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Approved worker discard history was not inserted");
        }
    }

    private ApprovedDiscardHistoryRecord mapApprovedDiscardHistory(
            ResultSet rs, int rowNumber) throws SQLException {
        StoredApprovedDiscardHistory stored = new StoredApprovedDiscardHistory(
                rs.getString("history_id"), rs.getString("scope_key"),
                rs.getString("tenant_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("environment_id"),
                rs.getString("run_id"), rs.getString("checkpoint_fingerprint"),
                rs.getString("quarantine_reason"), rs.getLong("consecutive_failures"),
                rs.getInt("quarantine_threshold"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("quarantined_at").toInstant(), rs.getString("reason_code"),
                rs.getString("resolution_owner"), rs.getString("approval_id"),
                rs.getString("approver_id"), rs.getString("approval_fingerprint"),
                rs.getLong("result_version"), rs.getTimestamp("acted_at").toInstant(),
                rs.getString("receipt_fingerprint"), rs.getString("record_fingerprint"));
        requireValid(stored);
        return stored.external();
    }

    private void requireValid(StoredApprovedDiscardHistory history) {
        WorkerAcquisitionScope scope;
        ApprovedDiscardHistoryRecord external;
        try {
            scope = history.scope();
            external = history.external();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored approved worker discard history is corrupt",
                    invalid);
        }
        String expectedReceipt = approvedDiscardReceiptFingerprint(scope, external.key(),
                external.ownerId(), external.approvalId(), external.approverId(),
                external.approvalFingerprint(), external.reasonCode(), external.version(),
                external.actedAt());
        if (!scopeKey(scope).equals(history.scopeKey())
                || external.ownerId().equals(external.approverId())
                || !expectedReceipt.equals(external.receiptFingerprint())
                || !approvedDiscardHistoryFingerprint(history).equals(
                        external.recordFingerprint())) {
            throw new IllegalStateException("Stored approved worker discard history is corrupt");
        }
    }

    private String approvedDiscardHistoryFingerprint(StoredApprovedDiscardHistory history) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineApprovedDiscardHistory.v1"),
                Map.entry("historyId", history.historyId()),
                Map.entry("scope", history.scope()), Map.entry("key", history.key()),
                Map.entry("quarantineReason", history.quarantineReason()),
                Map.entry("consecutiveFailures", history.consecutiveFailures()),
                Map.entry("quarantineThreshold", history.quarantineThreshold()),
                Map.entry("firstObservedAt", history.firstObservedAt()),
                Map.entry("quarantinedAt", history.quarantinedAt()),
                Map.entry("reasonCode", history.reasonCode()),
                Map.entry("ownerId", history.resolutionOwner()),
                Map.entry("approvalId", history.approvalId()),
                Map.entry("approverId", history.approverId()),
                Map.entry("approvalFingerprint", history.approvalFingerprint()),
                Map.entry("version", history.resultVersion()),
                Map.entry("actedAt", history.actedAt()),
                Map.entry("receiptFingerprint", history.receiptFingerprint())));
    }

    private String quarantineSelect() {
        return """
                SELECT q.scope_key, q.tenant_id, q.environment_id, q.organization_id,
                       q.project_id, q.run_id, q.checkpoint_fingerprint, q.reason,
                       q.consecutive_failures, q.quarantine_threshold, q.first_observed_at,
                       q.quarantined_at, q.record_fingerprint,
                       c.checkpoint_fingerprint AS control_checkpoint_fingerprint,
                       c.control_state, c.claim_owner, c.claim_token,
                       c.claim_token_key_id, c.claim_token_mac, c.claim_until,
                       c.control_version, c.record_version,
                       c.record_fingerprint AS control_record_fingerprint
                FROM rg_test_durable_worker_candidate_quarantines q
                LEFT JOIN rg_test_durable_worker_quarantine_controls c
                  ON c.scope_key = q.scope_key AND c.run_id = q.run_id
                """;
    }

    private QuarantineRecord mapQuarantine(
            ResultSet rs, Instant observedAt) throws SQLException {
        StoredQuarantine stored = mapStoredQuarantine(rs);
        requireValid(stored);
        String controlState = rs.getString("control_state");
        if (controlState == null) {
            return stored.external(QuarantineState.AVAILABLE, "", Instant.EPOCH, 0);
        }
        StoredControl control = new StoredControl(
                stored.scopeKey(), stored.runId(),
                rs.getString("control_checkpoint_fingerprint"), controlState,
                rs.getString("claim_owner"), rs.getString("claim_token"),
                rs.getString("claim_token_key_id"), rs.getString("claim_token_mac"),
                rs.getTimestamp("claim_until").toInstant(), rs.getLong("control_version"),
                rs.getInt("record_version"),
                rs.getString("control_record_fingerprint"));
        requireValid(control, stored);
        boolean liveClaim = control.state() == QuarantineState.CLAIMED
                && control.claimUntil().isAfter(observedAt);
        return stored.external(liveClaim ? QuarantineState.CLAIMED : QuarantineState.AVAILABLE,
                liveClaim ? control.claimOwner() : "",
                liveClaim ? control.claimUntil() : Instant.EPOCH, control.version());
    }

    private StoredQuarantine mapStoredQuarantine(ResultSet rs) throws SQLException {
        return new StoredQuarantine(
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("reason"),
                rs.getLong("consecutive_failures"), rs.getInt("quarantine_threshold"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("quarantined_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private Optional<StoredQuarantine> findQuarantine(
            WorkerAcquisitionScope scope, QuarantineKey key, boolean forUpdate) {
        List<StoredQuarantine> rows = jdbc.query("""
                        SELECT scope_key, tenant_id, environment_id, organization_id,
                               project_id, run_id, checkpoint_fingerprint, reason,
                               consecutive_failures, quarantine_threshold, first_observed_at,
                               quarantined_at, record_fingerprint
                        FROM rg_test_durable_worker_candidate_quarantines
                        WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                (rs, rowNumber) -> mapStoredQuarantine(rs),
                scopeKey(scope), key.runId(), key.checkpointFingerprint());
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine identity is not unique");
        }
        return rows.stream().findFirst();
    }

    private boolean lockExactCheckpoint(WorkerAcquisitionScope scope, QuarantineKey key) {
        List<String> rows = jdbc.query("""
                        SELECT checkpoint_json
                        FROM rg_test_durable_execution_checkpoints
                        WHERE tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ? AND run_id = ?
                        FOR UPDATE
                        """, (rs, rowNumber) -> rs.getString(1), scope.tenantId(),
                scope.environmentId(), scope.organizationId(), scope.projectId(), key.runId());
        if (rows.isEmpty()) {
            return false;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Durable checkpoint identity is not unique");
        }
        try {
            DurableTestExecutionCheckpoint checkpoint = objectMapper.readValue(
                    rows.getFirst(), DurableTestExecutionCheckpoint.class);
            checkpointIntegrity.requireValid(checkpoint);
            return scope.contains(checkpoint)
                    && checkpoint.runId().equals(key.runId())
                    && checkpoint.checkpointFingerprint().equals(key.checkpointFingerprint());
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored durable checkpoint is corrupt", corrupt);
        }
    }

    private Optional<StoredControl> findControl(
            WorkerAcquisitionScope scope, String runId, boolean forUpdate) {
        List<StoredControl> rows = jdbc.query("""
                        SELECT scope_key, run_id, checkpoint_fingerprint, control_state,
                               claim_owner, claim_token, claim_token_key_id, claim_token_mac,
                               claim_until, control_version, record_version, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_controls
                        WHERE scope_key = ? AND run_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""), this::mapControl,
                scopeKey(scope), runId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine control identity is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredControl mapControl(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredControl(rs.getString("scope_key"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("control_state"),
                rs.getString("claim_owner"), rs.getString("claim_token"),
                rs.getString("claim_token_key_id"), rs.getString("claim_token_mac"),
                rs.getTimestamp("claim_until").toInstant(), rs.getLong("control_version"),
                rs.getInt("record_version"),
                rs.getString("record_fingerprint"));
    }

    private StoredControl storedControl(
            StoredQuarantine quarantine,
            QuarantineState state,
            String owner,
            String token,
            Instant claimUntil,
            long version) {
        StoredControl unsealed = new StoredControl(quarantine.scopeKey(), quarantine.runId(),
                quarantine.checkpointFingerprint(), state.name(), owner, "", "", "",
                claimUntil, version, CONTROL_RECORD_VERSION, "");
        if (state == QuarantineState.CLAIMED) {
            WorkerQuarantineClaimTokenProtector.ActiveFence fence =
                    claimTokenProtector.protectActiveFence(
                            token, activeFenceAssociatedData(unsealed));
            unsealed = unsealed.withActiveFence(fence.keyId(), fence.mac());
        }
        return unsealed.withFingerprint(controlFingerprint(unsealed));
    }

    private void persistControl(StoredControl current, StoredControl next) {
        int changed;
        if (current == null) {
            changed = jdbc.update("""
                    INSERT INTO rg_test_durable_worker_quarantine_controls (
                        scope_key, run_id, checkpoint_fingerprint, control_state,
                        claim_owner, claim_token, claim_token_key_id, claim_token_mac,
                        claim_until, control_version, record_version, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, next.scopeKey(), next.runId(), next.checkpointFingerprint(),
                    next.state().name(), next.claimOwner(), next.legacyClaimToken(),
                    next.claimTokenKeyId(), next.claimTokenMac(),
                    Timestamp.from(next.claimUntil()), next.version(), next.recordVersion(),
                    next.recordFingerprint());
        } else {
            changed = jdbc.update("""
                    UPDATE rg_test_durable_worker_quarantine_controls
                    SET control_state = ?, claim_owner = ?, claim_token = ?,
                        claim_token_key_id = ?, claim_token_mac = ?, claim_until = ?,
                        control_version = ?, record_version = ?, record_fingerprint = ?
                    WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                      AND control_version = ? AND record_fingerprint = ?
                    """, next.state().name(), next.claimOwner(), next.legacyClaimToken(),
                    next.claimTokenKeyId(), next.claimTokenMac(),
                    Timestamp.from(next.claimUntil()), next.version(), next.recordVersion(),
                    next.recordFingerprint(),
                    current.scopeKey(), current.runId(), current.checkpointFingerprint(),
                    current.version(), current.recordFingerprint());
        }
        if (changed != 1) {
            throw new IllegalStateException("Worker quarantine control fence was rejected");
        }
    }

    private Optional<StoredClaimCommand> findClaimCommand(
            WorkerAcquisitionScope scope, String requestId, boolean forUpdate) {
        List<StoredClaimCommand> rows = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               claim_owner, claim_duration_seconds, request_fingerprint,
                               result_claim_token, result_claim_token_envelope,
                               result_version, result_claim_until,
                               created_at, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_claim_commands
                        WHERE scope_key = ? AND client_request_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""), this::mapClaimCommand,
                scopeKey(scope), requestId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine claim command is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredClaimCommand mapClaimCommand(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredClaimCommand(rs.getString("scope_key"),
                rs.getString("client_request_id"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("claim_owner"),
                rs.getLong("claim_duration_seconds"), rs.getString("request_fingerprint"),
                rs.getString("result_claim_token"),
                rs.getString("result_claim_token_envelope"), rs.getLong("result_version"),
                rs.getTimestamp("result_claim_until").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getString("record_fingerprint"));
    }

    private StoredClaimCommand storedClaimCommand(
            WorkerAcquisitionScope scope,
            String requestId,
            QuarantineKey key,
            String owner,
            Duration duration,
            String requestFingerprint,
            QuarantineClaim claim,
            Instant createdAt) {
        StoredClaimCommand unsealed = new StoredClaimCommand(scopeKey(scope), requestId,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                key.runId(), key.checkpointFingerprint(), owner, duration.toSeconds(),
                requestFingerprint, "", "", claim.version(), claim.claimUntil(), createdAt, "");
        String envelope = claimTokenProtector.protect(
                claim.claimToken(), claimTokenAssociatedData(unsealed));
        StoredClaimCommand protectedCommand = unsealed.withProtectedToken(envelope);
        return protectedCommand.withFingerprint(claimCommandFingerprint(protectedCommand));
    }

    private void insertClaimCommand(StoredClaimCommand command) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_claim_commands (
                    scope_key, client_request_id, tenant_id, environment_id,
                    organization_id, project_id, run_id, checkpoint_fingerprint,
                    claim_owner, claim_duration_seconds, request_fingerprint,
                    result_claim_token, result_claim_token_envelope,
                    result_version, result_claim_until,
                    created_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.scopeKey(), command.clientRequestId(), command.tenantId(),
                command.environmentId(), command.organizationId(), command.projectId(),
                command.runId(), command.checkpointFingerprint(), command.claimOwner(),
                command.claimDurationSeconds(), command.requestFingerprint(),
                command.legacyResultClaimToken(), command.resultClaimTokenEnvelope(),
                command.resultVersion(),
                Timestamp.from(command.resultClaimUntil()), Timestamp.from(command.createdAt()),
                command.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Worker quarantine claim command was not inserted");
        }
    }

    private void requireValid(StoredClaimCommand command) {
        WorkerAcquisitionScope scope;
        try {
            scope = command.scope();
            new QuarantineClaim(command.key(), command.claimOwner(), "validated-separately",
                    command.resultVersion(), command.resultClaimUntil());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine claim command is corrupt",
                    invalid);
        }
        boolean legacy = !command.legacyResultClaimToken().isBlank()
                && command.resultClaimTokenEnvelope().isBlank();
        boolean protectedToken = command.legacyResultClaimToken().isBlank()
                && !command.resultClaimTokenEnvelope().isBlank();
        if (legacy) {
            try {
                new QuarantineClaim(command.key(), command.claimOwner(),
                        command.legacyResultClaimToken(), command.resultVersion(),
                        command.resultClaimUntil());
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "Stored worker quarantine claim command is corrupt", invalid);
            }
        }
        boolean validFingerprint = legacy
                ? legacyClaimCommandFingerprint(command).equals(command.recordFingerprint())
                : protectedToken
                && claimCommandFingerprint(command).equals(command.recordFingerprint());
        if (!scopeKey(scope).equals(command.scopeKey())
                || command.claimDurationSeconds() < 1
                || command.claimDurationSeconds() > 3_600
                || !validFingerprint) {
            throw new IllegalStateException("Stored worker quarantine claim command is corrupt");
        }
        if (protectedToken) {
            claimTokenProtector.keyId(command.resultClaimTokenEnvelope());
        }
    }

    private String claimCommandFingerprint(StoredClaimCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimCommand.v2"),
                Map.entry("scope", command.scope()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("key", command.key()), Map.entry("ownerId", command.claimOwner()),
                Map.entry("claimDurationSeconds", command.claimDurationSeconds()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("resultClaimTokenEnvelope", command.resultClaimTokenEnvelope()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultClaimUntil", command.resultClaimUntil()),
                Map.entry("createdAt", command.createdAt())));
    }

    private String legacyClaimCommandFingerprint(StoredClaimCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimCommand.v1"),
                Map.entry("scope", command.scope()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("key", command.key()), Map.entry("ownerId", command.claimOwner()),
                Map.entry("claimDurationSeconds", command.claimDurationSeconds()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("resultClaimToken", command.legacyResultClaimToken()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultClaimUntil", command.resultClaimUntil()),
                Map.entry("createdAt", command.createdAt())));
    }

    private StoredClaimCommand normalizeClaimCommand(StoredClaimCommand command) {
        requireValid(command);
        boolean legacy = !command.legacyResultClaimToken().isBlank();
        if (!legacy && !claimTokenProtector.requiresRewrap(
                command.resultClaimTokenEnvelope())) {
            return command;
        }
        String token = legacy
                ? command.legacyResultClaimToken()
                : claimTokenProtector.unprotect(command.resultClaimTokenEnvelope(),
                claimTokenAssociatedData(command));
        StoredClaimCommand rewrapped = command.withProtectedToken(
                claimTokenProtector.protect(token, claimTokenAssociatedData(command)));
        rewrapped = rewrapped.withFingerprint(claimCommandFingerprint(rewrapped));
        int changed = jdbc.update("""
                UPDATE rg_test_durable_worker_quarantine_claim_commands
                SET result_claim_token = '', result_claim_token_envelope = ?,
                    record_fingerprint = ?
                WHERE scope_key = ? AND client_request_id = ? AND record_fingerprint = ?
                """, rewrapped.resultClaimTokenEnvelope(), rewrapped.recordFingerprint(),
                command.scopeKey(), command.clientRequestId(), command.recordFingerprint());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Worker quarantine claim token migration fence was rejected");
        }
        return rewrapped;
    }

    private QuarantineClaim claimFrom(StoredClaimCommand command) {
        try {
            String token = claimTokenProtector.unprotect(
                    command.resultClaimTokenEnvelope(), claimTokenAssociatedData(command));
            return new QuarantineClaim(command.key(), command.claimOwner(), token,
                    command.resultVersion(), command.resultClaimUntil());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Stored worker quarantine claim command token is unavailable or corrupt",
                    invalid);
        }
    }

    private String claimTokenAssociatedData(StoredClaimCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimTokenAad.v1"),
                Map.entry("scopeKey", command.scopeKey()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("runId", command.runId()),
                Map.entry("checkpointFingerprint", command.checkpointFingerprint()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultClaimUntil", command.resultClaimUntil())));
    }

    private void migrateAndRewrapClaimCommandTokens() {
        while (true) {
            Integer migrated = transactions.execute(status -> {
                List<StoredClaimCommand> candidates = jdbc.query("""
                                SELECT scope_key, client_request_id, tenant_id, environment_id,
                                       organization_id, project_id, run_id,
                                       checkpoint_fingerprint, claim_owner,
                                       claim_duration_seconds, request_fingerprint,
                                       result_claim_token, result_claim_token_envelope,
                                       result_version, result_claim_until, created_at,
                                       record_fingerprint
                                FROM rg_test_durable_worker_quarantine_claim_commands
                                WHERE result_claim_token <> ''
                                   OR result_claim_token_envelope NOT LIKE ?
                                ORDER BY scope_key, client_request_id
                                LIMIT ? FOR UPDATE
                                """, this::mapClaimCommand,
                        "v1." + claimTokenProtector.activeKeyId() + ".%",
                        TOKEN_MIGRATION_PAGE_SIZE);
                candidates.forEach(this::normalizeClaimCommand);
                return candidates.size();
            });
            if (migrated == null) {
                throw new IllegalStateException(
                        "Worker quarantine claim token migration returned no result");
            }
            if (migrated < TOKEN_MIGRATION_PAGE_SIZE) {
                return;
            }
        }
    }

    private void migrateAndRekeyActiveControlFences() {
        while (true) {
            Integer migrated = transactions.execute(status -> {
                List<StoredControl> candidates = jdbc.query("""
                                SELECT scope_key, run_id, checkpoint_fingerprint, control_state,
                                       claim_owner, claim_token, claim_token_key_id,
                                       claim_token_mac, claim_until, control_version,
                                       record_version, record_fingerprint
                                FROM rg_test_durable_worker_quarantine_controls
                                WHERE record_version <> ?
                                   OR (control_state = 'CLAIMED'
                                       AND claim_token_key_id <> ?)
                                ORDER BY scope_key, run_id
                                LIMIT ? FOR UPDATE
                                """, this::mapControl, CONTROL_RECORD_VERSION,
                        claimTokenProtector.activeKeyId(), TOKEN_MIGRATION_PAGE_SIZE);
                Instant observedAt = databaseNow();
                candidates.forEach(control -> normalizeActiveControlFence(control, observedAt));
                return candidates.size();
            });
            if (migrated == null) {
                throw new IllegalStateException(
                        "Worker quarantine active fence migration returned no result");
            }
            if (migrated < TOKEN_MIGRATION_PAGE_SIZE) {
                return;
            }
        }
    }

    private void normalizeActiveControlFence(StoredControl control, Instant observedAt) {
        StoredQuarantine quarantine = findQuarantineForControl(control);
        requireValid(control, quarantine);
        boolean expiredClaim = control.state() == QuarantineState.CLAIMED
                && !control.claimUntil().isAfter(observedAt);
        StoredControl migrated = new StoredControl(
                control.scopeKey(), control.runId(), control.checkpointFingerprint(),
                expiredClaim ? QuarantineState.AVAILABLE.name() : control.stateName(),
                expiredClaim ? "" : control.claimOwner(), "", "", "",
                expiredClaim ? Instant.EPOCH : control.claimUntil(), control.version(),
                CONTROL_RECORD_VERSION, "");
        if (control.state() == QuarantineState.CLAIMED && !expiredClaim) {
            StoredClaimCommand command = findClaimCommandForControl(control);
            requireValid(command);
            QuarantineClaim claim = claimFrom(command);
            if (!claim.key().equals(new QuarantineKey(
                    control.runId(), control.checkpointFingerprint()))
                    || !claim.ownerId().equals(control.claimOwner())
                    || claim.version() != control.version()
                    || !claim.claimUntil().equals(control.claimUntil())) {
                throw new IllegalStateException(
                        "Stored worker quarantine active fence command is inconsistent");
            }
            if (control.recordVersion() == 1) {
                if (!claim.claimToken().equals(control.legacyClaimToken())) {
                    throw new IllegalStateException(
                            "Stored worker quarantine active fence token is inconsistent");
                }
            } else if (!claimTokenProtector.matchesActiveFence(
                    claim.claimToken(), activeFenceAssociatedData(control),
                    control.claimTokenKeyId(), control.claimTokenMac())) {
                throw new IllegalStateException(
                        "Stored worker quarantine active fence authentication failed");
            }
            WorkerQuarantineClaimTokenProtector.ActiveFence fence =
                    claimTokenProtector.protectActiveFence(
                            claim.claimToken(), activeFenceAssociatedData(migrated));
            migrated = migrated.withActiveFence(fence.keyId(), fence.mac());
        }
        migrated = migrated.withFingerprint(controlFingerprint(migrated));
        int changed = jdbc.update("""
                UPDATE rg_test_durable_worker_quarantine_controls
                SET control_state = ?, claim_owner = ?, claim_token = '',
                    claim_token_key_id = ?, claim_token_mac = ?, claim_until = ?,
                    record_version = ?, record_fingerprint = ?
                WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                  AND control_version = ? AND record_fingerprint = ?
                """, migrated.state().name(), migrated.claimOwner(),
                migrated.claimTokenKeyId(), migrated.claimTokenMac(),
                Timestamp.from(migrated.claimUntil()),
                migrated.recordVersion(), migrated.recordFingerprint(),
                control.scopeKey(), control.runId(), control.checkpointFingerprint(),
                control.version(), control.recordFingerprint());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Worker quarantine active fence migration was rejected");
        }
    }

    private StoredQuarantine findQuarantineForControl(StoredControl control) {
        List<StoredQuarantine> rows = jdbc.query("""
                        SELECT scope_key, tenant_id, environment_id, organization_id,
                               project_id, run_id, checkpoint_fingerprint, reason,
                               consecutive_failures, quarantine_threshold, first_observed_at,
                               quarantined_at, record_fingerprint
                        FROM rg_test_durable_worker_candidate_quarantines
                        WHERE scope_key = ? AND run_id = ?
                        FOR UPDATE
                        """, (rs, rowNumber) -> mapStoredQuarantine(rs),
                control.scopeKey(), control.runId());
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Stored worker quarantine active fence has no unique quarantine");
        }
        StoredQuarantine quarantine = rows.getFirst();
        requireValid(quarantine);
        return quarantine;
    }

    private StoredClaimCommand findClaimCommandForControl(StoredControl control) {
        List<StoredClaimCommand> rows = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               claim_owner, claim_duration_seconds, request_fingerprint,
                               result_claim_token, result_claim_token_envelope,
                               result_version, result_claim_until,
                               created_at, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_claim_commands
                        WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                          AND claim_owner = ? AND result_version = ?
                          AND result_claim_until = ?
                        ORDER BY client_request_id
                        LIMIT 2 FOR UPDATE
                        """, this::mapClaimCommand, control.scopeKey(), control.runId(),
                control.checkpointFingerprint(), control.claimOwner(), control.version(),
                Timestamp.from(control.claimUntil()));
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Stored worker quarantine active fence has no unique claim command");
        }
        return normalizeClaimCommand(rows.getFirst());
    }

    private String resolutionRequestFingerprint(
            WorkerAcquisitionScope scope,
            QuarantineClaim claim,
            ResolutionAction action,
            String reasonCode) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineResolutionIntent.v1"),
                Map.entry("scope", scope), Map.entry("key", claim.key()),
                Map.entry("ownerId", claim.ownerId()),
                Map.entry("claimToken", claim.claimToken()),
                Map.entry("claimVersion", claim.version()),
                Map.entry("claimUntil", claim.claimUntil()),
                Map.entry("action", action.name()), Map.entry("reasonCode", reasonCode)));
    }

    private QuarantineResolutionReceipt resolutionReceipt(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String owner,
            ResolutionAction action,
            String reasonCode,
            long version,
            Instant actedAt) {
        String fingerprint = resolutionReceiptFingerprint(
                scope, key, owner, action, reasonCode, version, actedAt);
        return new QuarantineResolutionReceipt(
                key, owner, action, reasonCode, version, actedAt, fingerprint);
    }

    private String resolutionReceiptFingerprint(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String owner,
            ResolutionAction action,
            String reasonCode,
            long version,
            Instant actedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineResolutionReceipt.v1"),
                Map.entry("scope", scope), Map.entry("key", key),
                Map.entry("ownerId", owner), Map.entry("action", action.name()),
                Map.entry("reasonCode", reasonCode), Map.entry("version", version),
                Map.entry("actedAt", actedAt)));
    }

    private Optional<StoredResolutionCommand> findResolutionCommand(
            WorkerAcquisitionScope scope, String requestId, boolean forUpdate) {
        List<StoredResolutionCommand> rows = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               resolution_owner, resolution_action, reason_code,
                               request_fingerprint, result_version, result_acted_at,
                               result_receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_resolutions
                        WHERE scope_key = ? AND client_request_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""), this::mapResolutionCommand,
                scopeKey(scope), requestId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Worker quarantine resolution command is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredResolutionCommand mapResolutionCommand(
            ResultSet rs, int rowNumber) throws SQLException {
        return new StoredResolutionCommand(rs.getString("scope_key"),
                rs.getString("client_request_id"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("resolution_owner"),
                rs.getString("resolution_action"), rs.getString("reason_code"),
                rs.getString("request_fingerprint"), rs.getLong("result_version"),
                rs.getTimestamp("result_acted_at").toInstant(),
                rs.getString("result_receipt_fingerprint"),
                rs.getString("record_fingerprint"));
    }

    private StoredResolutionCommand storedResolutionCommand(
            WorkerAcquisitionScope scope,
            String requestId,
            QuarantineKey key,
            String owner,
            ResolutionAction action,
            String reasonCode,
            String requestFingerprint,
            QuarantineResolutionReceipt receipt) {
        StoredResolutionCommand unsealed = new StoredResolutionCommand(scopeKey(scope),
                requestId, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), key.runId(), key.checkpointFingerprint(), owner,
                action.name(), reasonCode, requestFingerprint, receipt.version(),
                receipt.actedAt(), receipt.receiptFingerprint(), "");
        return unsealed.withFingerprint(resolutionCommandFingerprint(unsealed));
    }

    private void insertResolutionCommand(StoredResolutionCommand command) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_resolutions (
                    scope_key, client_request_id, tenant_id, environment_id,
                    organization_id, project_id, run_id, checkpoint_fingerprint,
                    resolution_owner, resolution_action, reason_code, request_fingerprint,
                    result_version, result_acted_at, result_receipt_fingerprint,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.scopeKey(), command.clientRequestId(), command.tenantId(),
                command.environmentId(), command.organizationId(), command.projectId(),
                command.runId(), command.checkpointFingerprint(), command.resolutionOwner(),
                command.resolutionAction(), command.reasonCode(), command.requestFingerprint(),
                command.resultVersion(), Timestamp.from(command.resultActedAt()),
                command.resultReceiptFingerprint(), command.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Worker quarantine resolution command was not inserted");
        }
    }

    private void requireValid(StoredResolutionCommand command) {
        WorkerAcquisitionScope scope;
        QuarantineResolutionReceipt receipt;
        try {
            scope = command.scope();
            receipt = command.receipt();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Stored worker quarantine resolution command is corrupt", invalid);
        }
        String expectedReceipt = resolutionReceiptFingerprint(
                scope, receipt.key(), receipt.ownerId(), receipt.action(),
                receipt.reasonCode(), receipt.version(), receipt.actedAt());
        if (!scopeKey(scope).equals(command.scopeKey())
                || !expectedReceipt.equals(receipt.receiptFingerprint())
                || !resolutionCommandFingerprint(command).equals(command.recordFingerprint())) {
            throw new IllegalStateException(
                    "Stored worker quarantine resolution command is corrupt");
        }
    }

    private String resolutionCommandFingerprint(StoredResolutionCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineResolutionCommand.v1"),
                Map.entry("scope", command.scope()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("key", command.key()),
                Map.entry("ownerId", command.resolutionOwner()),
                Map.entry("action", command.resolutionAction()),
                Map.entry("reasonCode", command.reasonCode()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultActedAt", command.resultActedAt()),
                Map.entry("resultReceiptFingerprint", command.resultReceiptFingerprint())));
    }

    private StoredHistory storedHistory(
            WorkerAcquisitionScope scope,
            StoredQuarantine quarantine,
            QuarantineResolutionReceipt receipt) {
        ActiveWorkerCandidateQuarantine active = quarantine.quarantine();
        StoredHistory unsealed = new StoredHistory(UUID.randomUUID().toString(), scopeKey(scope),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                quarantine.runId(), quarantine.checkpointFingerprint(), active.reason().name(),
                active.consecutiveFailures(), active.quarantineThreshold(),
                active.firstObservedAt(), active.quarantinedAt(), receipt.action().name(),
                receipt.reasonCode(), receipt.ownerId(), receipt.version(), receipt.actedAt(),
                receipt.receiptFingerprint(), "");
        return unsealed.withFingerprint(historyFingerprint(unsealed));
    }

    private void insertHistory(StoredHistory history) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_history (
                    history_id, scope_key, tenant_id, environment_id, organization_id,
                    project_id, run_id, checkpoint_fingerprint, quarantine_reason,
                    consecutive_failures, quarantine_threshold, first_observed_at,
                    quarantined_at, resolution_action, reason_code, resolution_owner,
                    result_version, acted_at, receipt_fingerprint, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, history.historyId(), history.scopeKey(), history.tenantId(),
                history.environmentId(), history.organizationId(), history.projectId(),
                history.runId(), history.checkpointFingerprint(), history.quarantineReason(),
                history.consecutiveFailures(), history.quarantineThreshold(),
                Timestamp.from(history.firstObservedAt()), Timestamp.from(history.quarantinedAt()),
                history.resolutionAction(), history.reasonCode(), history.resolutionOwner(),
                history.resultVersion(), Timestamp.from(history.actedAt()),
                history.receiptFingerprint(), history.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Worker quarantine history was not inserted");
        }
    }

    private QuarantineHistoryRecord mapHistory(ResultSet rs, int rowNumber) throws SQLException {
        StoredHistory stored = new StoredHistory(rs.getString("history_id"),
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("quarantine_reason"),
                rs.getLong("consecutive_failures"), rs.getInt("quarantine_threshold"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("quarantined_at").toInstant(),
                rs.getString("resolution_action"), rs.getString("reason_code"),
                rs.getString("resolution_owner"), rs.getLong("result_version"),
                rs.getTimestamp("acted_at").toInstant(), rs.getString("receipt_fingerprint"),
                rs.getString("record_fingerprint"));
        requireValid(stored);
        return stored.external();
    }

    private void requireValid(StoredHistory history) {
        WorkerAcquisitionScope scope;
        QuarantineHistoryRecord external;
        try {
            scope = history.scope();
            external = history.external();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine history is corrupt", invalid);
        }
        String expectedReceipt = resolutionReceiptFingerprint(scope, external.key(),
                external.ownerId(), external.action(), external.reasonCode(), external.version(),
                external.actedAt());
        if (!scopeKey(scope).equals(history.scopeKey())
                || !expectedReceipt.equals(history.receiptFingerprint())
                || !historyFingerprint(history).equals(history.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine history is corrupt");
        }
    }

    private void requireValidHistoryRecord(QuarantineHistoryRecord history) {
        Objects.requireNonNull(history, "history");
    }

    private String historyFingerprint(StoredHistory history) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineHistory.v1"),
                Map.entry("historyId", history.historyId()), Map.entry("scope", history.scope()),
                Map.entry("key", history.key()),
                Map.entry("quarantineReason", history.quarantineReason()),
                Map.entry("consecutiveFailures", history.consecutiveFailures()),
                Map.entry("quarantineThreshold", history.quarantineThreshold()),
                Map.entry("firstObservedAt", history.firstObservedAt()),
                Map.entry("quarantinedAt", history.quarantinedAt()),
                Map.entry("action", history.resolutionAction()),
                Map.entry("reasonCode", history.reasonCode()),
                Map.entry("ownerId", history.resolutionOwner()),
                Map.entry("version", history.resultVersion()),
                Map.entry("actedAt", history.actedAt()),
                Map.entry("receiptFingerprint", history.receiptFingerprint())));
    }

    private void deleteQuarantine(StoredQuarantine quarantine) {
        int deleted = jdbc.update("""
                DELETE FROM rg_test_durable_worker_candidate_quarantines
                WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                  AND record_fingerprint = ?
                """, quarantine.scopeKey(), quarantine.runId(),
                quarantine.checkpointFingerprint(), quarantine.recordFingerprint());
        if (deleted != 1) {
            throw new IllegalStateException("Worker quarantine changed while discarding");
        }
    }

    private void requireValid(StoredQuarantine stored) {
        WorkerAcquisitionScope scope;
        ActiveWorkerCandidateQuarantine quarantine;
        try {
            scope = stored.scope();
            quarantine = stored.quarantine();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine is corrupt", invalid);
        }
        String expected = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerCandidateQuarantine.v1"),
                Map.entry("scope", scope), Map.entry("runId", stored.runId()),
                Map.entry("checkpointFingerprint", stored.checkpointFingerprint()),
                Map.entry("reason", quarantine.reason().name()),
                Map.entry("consecutiveFailures", quarantine.consecutiveFailures()),
                Map.entry("quarantineThreshold", quarantine.quarantineThreshold()),
                Map.entry("firstObservedAt", quarantine.firstObservedAt()),
                Map.entry("quarantinedAt", quarantine.quarantinedAt())));
        if (!scopeKey(scope).equals(stored.scopeKey())
                || !expected.equals(stored.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine is corrupt");
        }
    }

    private void requireValid(StoredControl control, StoredQuarantine quarantine) {
        boolean valid;
        try {
            boolean available = control.state() == QuarantineState.AVAILABLE;
            boolean legacy = control.recordVersion() == 1;
            boolean current = control.recordVersion() == CONTROL_RECORD_VERSION;
            boolean availableShape = control.claimOwner().isBlank()
                    && control.legacyClaimToken().isBlank()
                    && control.claimTokenKeyId().isBlank()
                    && control.claimTokenMac().isBlank()
                    && control.claimUntil().equals(Instant.EPOCH);
            boolean legacyClaimShape = !control.claimOwner().isBlank()
                    && !control.legacyClaimToken().isBlank()
                    && control.claimTokenKeyId().isBlank()
                    && control.claimTokenMac().isBlank()
                    && control.claimUntil().isAfter(Instant.EPOCH);
            boolean currentClaimShape = !control.claimOwner().isBlank()
                    && control.legacyClaimToken().isBlank()
                    && !control.claimTokenKeyId().isBlank()
                    && !control.claimTokenMac().isBlank()
                    && control.claimUntil().isAfter(Instant.EPOCH);
            boolean shapeValid = available
                    ? availableShape
                    : legacy ? legacyClaimShape : current && currentClaimShape;
            valid = (legacy || current)
                    && control.scopeKey().equals(quarantine.scopeKey())
                    && control.runId().equals(quarantine.runId())
                    && control.checkpointFingerprint().equals(
                    quarantine.checkpointFingerprint())
                    && control.version() >= 0 && shapeValid
                    && controlFingerprint(control).equals(control.recordFingerprint());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine control is corrupt", invalid);
        }
        if (!valid) {
            throw new IllegalStateException("Stored worker quarantine control is corrupt");
        }
    }

    private String controlFingerprint(StoredControl control) {
        if (control.recordVersion() == 1) {
            return legacyControlFingerprint(control);
        }
        if (control.recordVersion() != CONTROL_RECORD_VERSION) {
            throw new IllegalStateException("Worker quarantine control version is unsupported");
        }
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineControl.v2"),
                Map.entry("scopeKey", control.scopeKey()),
                Map.entry("runId", control.runId()),
                Map.entry("checkpointFingerprint", control.checkpointFingerprint()),
                Map.entry("state", control.state().name()),
                Map.entry("claimOwner", control.claimOwner()),
                Map.entry("claimTokenKeyId", control.claimTokenKeyId()),
                Map.entry("claimTokenMac", control.claimTokenMac()),
                Map.entry("claimUntil", control.claimUntil()),
                Map.entry("version", control.version()),
                Map.entry("recordVersion", control.recordVersion())));
    }

    private String legacyControlFingerprint(StoredControl control) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineControl.v1"),
                Map.entry("scopeKey", control.scopeKey()),
                Map.entry("runId", control.runId()),
                Map.entry("checkpointFingerprint", control.checkpointFingerprint()),
                Map.entry("state", control.state().name()),
                Map.entry("claimOwner", control.claimOwner()),
                Map.entry("claimToken", control.legacyClaimToken()),
                Map.entry("claimUntil", control.claimUntil()),
                Map.entry("version", control.version())));
    }

    private boolean matchesActiveClaim(
            StoredControl control, QuarantineClaim claim, Instant now) {
        return control.state() == QuarantineState.CLAIMED
                && control.claimOwner().equals(claim.ownerId())
                && control.version() == claim.version()
                && control.claimUntil().equals(claim.claimUntil())
                && control.claimUntil().isAfter(now)
                && claimTokenProtector.matchesActiveFence(
                claim.claimToken(), activeFenceAssociatedData(control),
                control.claimTokenKeyId(), control.claimTokenMac());
    }

    private String activeFenceAssociatedData(StoredControl control) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineActiveFenceAad.v1"),
                Map.entry("scopeKey", control.scopeKey()),
                Map.entry("runId", control.runId()),
                Map.entry("checkpointFingerprint", control.checkpointFingerprint()),
                Map.entry("state", control.state().name()),
                Map.entry("claimOwner", control.claimOwner()),
                Map.entry("claimUntil", control.claimUntil()),
                Map.entry("version", control.version())));
    }

    private String scopeKey(WorkerAcquisitionScope scope) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.durableWorkerScanScope.v1", "scope", scope));
    }

    private void initializeRetentionState() {
        try {
            Instant now = databaseNow();
            RetentionState initial = new RetentionState("", "", 0, Instant.EPOCH,
                    0, 0, 0, 0, null, now, "");
            initial = initial.withFingerprint(retentionStateFingerprint(initial));
            jdbc.update("""
                    INSERT INTO rg_test_durable_worker_quarantine_retention (
                        job_name, lease_owner, lease_token, lease_epoch, lease_until, revision,
                        total_tombstoned, total_tombstones_purged, total_history_purged,
                        last_success_at, updated_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, RETENTION_JOB_NAME, initial.leaseOwner(), initial.leaseToken(),
                    initial.leaseEpoch(), Timestamp.from(initial.leaseUntil()), initial.revision(),
                    initial.totalTombstoned(), initial.totalTombstonesPurged(),
                    initial.totalHistoryPurged(), initial.lastSuccessAt(),
                    Timestamp.from(initial.updatedAt()), initial.recordFingerprint());
        } catch (DuplicateKeyException alreadyInitialized) {
            // Another replica created the singleton row first.
        }
    }

    private RetentionState requireRetentionState(boolean lock) {
        List<RetentionState> rows = jdbc.query("""
                        SELECT lease_owner, lease_token, lease_epoch, lease_until, revision,
                               total_tombstoned, total_tombstones_purged,
                               total_history_purged, last_success_at, updated_at,
                               record_fingerprint
                        FROM rg_test_durable_worker_quarantine_retention
                        WHERE job_name = ?
                        """ + (lock ? " FOR UPDATE" : ""), this::mapRetentionState,
                RETENTION_JOB_NAME);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Worker quarantine retention state is not initialized");
        }
        RetentionState state = rows.getFirst();
        requireValid(state);
        return state;
    }

    private RetentionState mapRetentionState(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp lastSuccess = rs.getTimestamp("last_success_at");
        return new RetentionState(rs.getString("lease_owner"),
                rs.getString("lease_token"), rs.getLong("lease_epoch"),
                rs.getTimestamp("lease_until").toInstant(), rs.getLong("revision"),
                rs.getLong("total_tombstoned"), rs.getLong("total_tombstones_purged"),
                rs.getLong("total_history_purged"),
                lastSuccess == null ? null : lastSuccess.toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private void requireValid(RetentionState state) {
        boolean idle = state.leaseOwner() != null && state.leaseOwner().isBlank()
                && state.leaseToken() != null && state.leaseToken().isBlank()
                && state.leaseUntil() != null && state.leaseUntil().equals(Instant.EPOCH);
        boolean owned = state.leaseOwner() != null && !state.leaseOwner().isBlank()
                && state.leaseToken() != null && !state.leaseToken().isBlank()
                && state.leaseUntil() != null && state.leaseUntil().isAfter(Instant.EPOCH);
        if ((!idle && !owned) || state.leaseEpoch() < 0 || state.revision() < 0
                || state.totalTombstoned() < 0 || state.totalTombstonesPurged() < 0
                || state.totalHistoryPurged() < 0 || state.updatedAt() == null
                || !state.updatedAt().isAfter(Instant.EPOCH)
                || (state.lastSuccessAt() != null
                && (state.lastSuccessAt().isAfter(state.updatedAt())
                || !state.lastSuccessAt().isAfter(Instant.EPOCH)))
                || !retentionStateFingerprint(state).equals(state.recordFingerprint())) {
            throw new IllegalStateException(
                    "Stored worker quarantine retention authority is corrupt");
        }
    }

    private String retentionStateFingerprint(RetentionState state) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRetentionState.v1"),
                Map.entry("jobName", RETENTION_JOB_NAME),
                Map.entry("leaseOwner", state.leaseOwner()),
                Map.entry("leaseToken", state.leaseToken()),
                Map.entry("leaseEpoch", state.leaseEpoch()),
                Map.entry("leaseUntil", state.leaseUntil()),
                Map.entry("revision", state.revision()),
                Map.entry("totalTombstoned", state.totalTombstoned()),
                Map.entry("totalTombstonesPurged", state.totalTombstonesPurged()),
                Map.entry("totalHistoryPurged", state.totalHistoryPurged()),
                Map.entry("lastSuccessAt", state.lastSuccessAt() == null
                        ? "" : state.lastSuccessAt()),
                Map.entry("updatedAt", state.updatedAt())));
    }

    private void requireLiveRetentionFence(
            RetentionState state, RetentionLease lease, Instant now) {
        if (!state.leaseOwner().equals(lease.ownerId())
                || !state.leaseToken().equals(lease.token())
                || state.leaseEpoch() != lease.epoch()
                || !state.leaseUntil().isAfter(now)) {
            throw new IllegalStateException(
                    "Worker quarantine retention fence is stale or expired");
        }
    }

    private boolean releaseRetentionLease(RetentionLease lease) {
        Boolean result = transactions.execute(status -> {
            RetentionState current = requireRetentionState(true);
            if (!current.leaseOwner().equals(lease.ownerId())
                    || !current.leaseToken().equals(lease.token())
                    || current.leaseEpoch() != lease.epoch()) {
                return false;
            }
            Instant now = databaseNow();
            RetentionState next = new RetentionState("", "", current.leaseEpoch(),
                    Instant.EPOCH, Math.addExact(current.revision(), 1),
                    current.totalTombstoned(), current.totalTombstonesPurged(),
                    current.totalHistoryPurged(), current.lastSuccessAt(), now, "");
            next = next.withFingerprint(retentionStateFingerprint(next));
            int updated = jdbc.update("""
                    UPDATE rg_test_durable_worker_quarantine_retention
                    SET lease_owner = '', lease_token = '', lease_until = ?,
                        revision = ?, updated_at = ?, record_fingerprint = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_epoch = ? AND revision = ? AND record_fingerprint = ?
                    """, Timestamp.from(next.leaseUntil()), next.revision(),
                    Timestamp.from(next.updatedAt()), next.recordFingerprint(),
                    RETENTION_JOB_NAME, lease.ownerId(), lease.token(), lease.epoch(),
                    current.revision(), current.recordFingerprint());
            return updated == 1;
        });
        return Boolean.TRUE.equals(result);
    }

    private Instant databaseNow() {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (rs, rowNumber) -> rs.getTimestamp(1).toInstant());
        if (now == null) {
            throw new IllegalStateException("Test-runtime database did not provide its clock");
        }
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    private static int bounded(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Worker quarantine page size must be between 1 and 1000");
        }
        return limit;
    }

    private static Duration boundedLease(Duration duration) {
        Duration safe = Objects.requireNonNull(duration, "claimDuration");
        if (safe.compareTo(Duration.ofSeconds(1)) < 0
                || safe.compareTo(Duration.ofHours(1)) > 0
                || safe.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Worker quarantine claim duration must be whole seconds from 1 through 3600");
        }
        return safe;
    }

    private static Duration boundedApproval(Duration duration) {
        Duration safe = Objects.requireNonNull(duration, "approvalDuration");
        if (safe.compareTo(Duration.ofSeconds(1)) < 0
                || safe.compareTo(Duration.ofMinutes(15)) > 0
                || safe.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Discard approval duration must be whole seconds from 1 through 900");
        }
        return safe;
    }

    private static Duration boundedRetention(
            Duration duration, Duration minimum, String name) {
        Duration safe = Objects.requireNonNull(duration, name);
        if (safe.compareTo(minimum) < 0 || safe.compareTo(MAX_RETENTION) > 0
                || safe.getNano() != 0) {
            throw new IllegalArgumentException(name + " must be whole seconds from "
                    + minimum + " through " + MAX_RETENTION);
        }
        return safe;
    }

    private static Duration boundedRetentionLease(Duration duration) {
        Duration safe = Objects.requireNonNull(duration, "retentionLeaseDuration");
        if (safe.compareTo(Duration.ofSeconds(1)) < 0
                || safe.compareTo(Duration.ofHours(1)) > 0 || safe.getNano() != 0) {
            throw new IllegalArgumentException(
                    "retentionLeaseDuration must be whole seconds from 1 through 3600");
        }
        return safe;
    }

    private static Instant earlierOf(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static String reason(String value) {
        String safe = required(value, "reasonCode", 128).toUpperCase(Locale.ROOT);
        if (!REASON_CODE.matcher(safe).matches()) {
            throw new IllegalArgumentException("Worker quarantine reasonCode is invalid");
        }
        return safe;
    }

    /** Stable outcome of one scheduled worker-quarantine retention attempt. */
    public enum RetentionStatus {
        /** This replica held the live database fence and committed one bounded page. */
        COMPLETED,
        /** Another replica still owns the database-clock retention lease. */
        LEASE_BUSY
    }

    /**
     * Aggregate of one atomically committed retention page.
     *
     * @param tombstoned detailed command or approval rows replaced by payload-free tombstones
     * @param tombstonesPurged expired idempotency tombstones physically removed
     * @param historiesPurged expired token-free history rows physically removed
     * @param completedAt database-clock commit time
     */
    public record RetentionResult(
            int tombstoned,
            int tombstonesPurged,
            int historiesPurged,
            Instant completedAt) {
        /** Validates non-negative bounded aggregate values. */
        public RetentionResult {
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            if (tombstoned < 0 || tombstonesPurged < 0 || historiesPurged < 0) {
                throw new IllegalArgumentException(
                        "Worker quarantine retention counts must be non-negative");
            }
        }
    }

    /**
     * Result of one scheduled retention attempt.
     *
     * @param status committed or lease-busy
     * @param result aggregate for a committed page, otherwise {@code null}
     */
    public record RetentionAttempt(RetentionStatus status, RetentionResult result) {
        /** Enforces that only completed attempts carry an aggregate. */
        public RetentionAttempt {
            status = Objects.requireNonNull(status, "status");
            if ((status == RetentionStatus.COMPLETED) != (result != null)) {
                throw new IllegalArgumentException(
                        "Invalid worker quarantine retention attempt");
            }
        }

        private static RetentionAttempt completed(RetentionResult result) {
            return new RetentionAttempt(RetentionStatus.COMPLETED,
                    Objects.requireNonNull(result, "result"));
        }

        private static RetentionAttempt busy() {
            return new RetentionAttempt(RetentionStatus.LEASE_BUSY, null);
        }
    }

    /**
     * Payload-free retention control snapshot for readiness and bounded-cardinality telemetry.
     *
     * @param leaseOwner current replica owner, blank when idle
     * @param leaseEpoch monotonically increasing fencing generation
     * @param leaseUntil database-clock lease deadline
     * @param revision monotonically increasing control revision
     * @param totalTombstoned cumulative detailed commands and approvals removed
     * @param totalTombstonesPurged cumulative expired idempotency tombstones removed
     * @param totalHistoryPurged cumulative expired history rows removed
     * @param tombstoneRecords current payload-free tombstone count
     * @param lastSuccessAt last committed retention page, or {@code null}
     * @param updatedAt last database-clock control mutation
     */
    public record RetentionSnapshot(
            String leaseOwner,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            long totalTombstoned,
            long totalTombstonesPurged,
            long totalHistoryPurged,
            long tombstoneRecords,
            Instant lastSuccessAt,
            Instant updatedAt) {
        /** Validates aggregate-only operational state. */
        public RetentionSnapshot {
            leaseOwner = leaseOwner == null ? "" : leaseOwner;
            leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (leaseEpoch < 0 || revision < 0 || totalTombstoned < 0
                    || totalTombstonesPurged < 0 || totalHistoryPurged < 0
                    || tombstoneRecords < 0) {
                throw new IllegalArgumentException(
                        "Invalid worker quarantine retention snapshot");
            }
        }
    }

    /** Stable operational state of one automatic quarantine. */
    public enum QuarantineState {
        /** No live maintenance owner; a verified operator may claim the record. */
        AVAILABLE,
        /** A maintenance owner holds a database-clock lease. */
        CLAIMED
    }

    /** Stable result vocabulary for idempotent quarantine claims. */
    public enum ClaimDisposition {
        /** A new claim, command receipt, and audit mutation committed. */
        CLAIMED,
        /** The exact immutable command result was returned. */
        IDEMPOTENT_REPLAY,
        /** Another unexpired owner or missing quarantine prevents a claim. */
        NOT_ACTIONABLE,
        /** The request ID already identifies different intent. */
        IDEMPOTENCY_CONFLICT,
        /** Exact replay material expired, but its tombstone still forbids request-ID reuse. */
        REPLAY_WINDOW_EXPIRED,
        /** The run no longer has the exact checkpoint closure named by the request. */
        STALE_CHECKPOINT
    }

    /**
     * Exact identity of one automatic worker quarantine.
     *
     * @param runId durable execution identity
     * @param checkpointFingerprint exact isolated checkpoint closure
     */
    public record QuarantineKey(String runId, String checkpointFingerprint) {
        /** Validates bounded exact-checkpoint identity. */
        public QuarantineKey {
            runId = required(runId, "runId", 255);
            checkpointFingerprint = required(
                    checkpointFingerprint, "checkpointFingerprint", 80);
        }
    }

    /**
     * Server-issued fence for one maintenance claim.
     *
     * @param key exact quarantine identity
     * @param ownerId verified maintenance actor
     * @param claimToken unguessable response-only secret
     * @param version positive monotonic control generation
     * @param claimUntil database-clock lease deadline
     */
    public record QuarantineClaim(
            QuarantineKey key,
            String ownerId,
            String claimToken,
            long version,
            Instant claimUntil) {
        /** Validates a complete owner fence. */
        public QuarantineClaim {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "ownerId", 255);
            claimToken = required(claimToken, "claimToken", 255);
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            if (version <= 0 || !claimUntil.isAfter(Instant.EPOCH)) {
                throw new IllegalArgumentException("Invalid worker quarantine claim");
            }
        }
    }

    /**
     * Result of one idempotent maintenance claim.
     *
     * @param disposition stable outcome
     * @param claim exact claim for successful or replayed outcomes, otherwise {@code null}
     */
    public record QuarantineClaimResult(
            ClaimDisposition disposition, QuarantineClaim claim) {
        /** Enforces that only successful outcomes carry a secret fence. */
        public QuarantineClaimResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesClaim = disposition == ClaimDisposition.CLAIMED
                    || disposition == ClaimDisposition.IDEMPOTENT_REPLAY;
            if (carriesClaim != (claim != null)) {
                throw new IllegalArgumentException("Invalid worker quarantine claim result");
            }
        }

        private static QuarantineClaimResult claimed(QuarantineClaim claim) {
            return new QuarantineClaimResult(ClaimDisposition.CLAIMED, claim);
        }

        private static QuarantineClaimResult replay(QuarantineClaim claim) {
            return new QuarantineClaimResult(ClaimDisposition.IDEMPOTENT_REPLAY, claim);
        }

        private static QuarantineClaimResult notActionable() {
            return new QuarantineClaimResult(ClaimDisposition.NOT_ACTIONABLE, null);
        }

        private static QuarantineClaimResult conflict() {
            return new QuarantineClaimResult(ClaimDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        private static QuarantineClaimResult replayWindowExpired() {
            return new QuarantineClaimResult(ClaimDisposition.REPLAY_WINDOW_EXPIRED, null);
        }

        private static QuarantineClaimResult staleCheckpoint() {
            return new QuarantineClaimResult(ClaimDisposition.STALE_CHECKPOINT, null);
        }
    }

    /** Stable manual action vocabulary for an exact-checkpoint quarantine. */
    public enum ResolutionAction {
        /** Release maintenance ownership while preserving worker suppression. */
        RELEASE,
        /** Delete the exact quarantine and allow the checkpoint to be reconsidered. */
        DISCARD
    }

    /** Stable result vocabulary for idempotent quarantine resolutions. */
    public enum ResolutionDisposition {
        /** A new action, receipt, history row, and audit mutation committed. */
        RESOLVED,
        /** The exact immutable action receipt was returned. */
        IDEMPOTENT_REPLAY,
        /** Owner, token, version, expiry, or active-record fencing failed. */
        FENCE_REJECTED,
        /** The request ID already identifies different intent. */
        IDEMPOTENCY_CONFLICT,
        /** Exact replay material expired, but its tombstone still forbids request-ID reuse. */
        REPLAY_WINDOW_EXPIRED,
        /** The run no longer has the exact checkpoint closure named by the claim. */
        STALE_CHECKPOINT,
        /** A new discard must use the independent two-person approval protocol. */
        APPROVAL_REQUIRED
    }

    /**
     * Immutable token-free receipt for one manual quarantine action.
     *
     * @param key exact quarantine identity
     * @param ownerId verified maintenance actor
     * @param action release or discard
     * @param reasonCode bounded non-payload operational rationale
     * @param version resulting maintenance generation
     * @param actedAt database-clock commit time
     * @param receiptFingerprint canonical immutable receipt fingerprint
     */
    public record QuarantineResolutionReceipt(
            QuarantineKey key,
            String ownerId,
            ResolutionAction action,
            String reasonCode,
            long version,
            Instant actedAt,
            String receiptFingerprint) {
        /** Validates a complete token-free action receipt. */
        public QuarantineResolutionReceipt {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "ownerId", 255);
            action = Objects.requireNonNull(action, "action");
            reasonCode = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
            actedAt = Objects.requireNonNull(actedAt, "actedAt");
            receiptFingerprint = required(
                    receiptFingerprint, "receiptFingerprint", 80);
            if (!REASON_CODE.matcher(reasonCode).matches() || version <= 0) {
                throw new IllegalArgumentException("Invalid worker quarantine action receipt");
            }
        }
    }

    /**
     * Result of one idempotent quarantine resolution.
     *
     * @param disposition stable action outcome
     * @param receipt token-free receipt for resolved or replayed outcomes, otherwise {@code null}
     */
    public record QuarantineResolutionResult(
            ResolutionDisposition disposition, QuarantineResolutionReceipt receipt) {
        /** Enforces that only successful outcomes carry a receipt. */
        public QuarantineResolutionResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesReceipt = disposition == ResolutionDisposition.RESOLVED
                    || disposition == ResolutionDisposition.IDEMPOTENT_REPLAY;
            if (carriesReceipt != (receipt != null)) {
                throw new IllegalArgumentException("Invalid worker quarantine resolution result");
            }
        }

        private static QuarantineResolutionResult resolved(
                QuarantineResolutionReceipt receipt) {
            return new QuarantineResolutionResult(ResolutionDisposition.RESOLVED, receipt);
        }

        private static QuarantineResolutionResult replay(
                QuarantineResolutionReceipt receipt) {
            return new QuarantineResolutionResult(
                    ResolutionDisposition.IDEMPOTENT_REPLAY, receipt);
        }

        private static QuarantineResolutionResult fenceRejected() {
            return new QuarantineResolutionResult(ResolutionDisposition.FENCE_REJECTED, null);
        }

        private static QuarantineResolutionResult conflict() {
            return new QuarantineResolutionResult(
                    ResolutionDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        private static QuarantineResolutionResult replayWindowExpired() {
            return new QuarantineResolutionResult(
                    ResolutionDisposition.REPLAY_WINDOW_EXPIRED, null);
        }

        private static QuarantineResolutionResult staleCheckpoint() {
            return new QuarantineResolutionResult(ResolutionDisposition.STALE_CHECKPOINT, null);
        }

        private static QuarantineResolutionResult approvalRequired() {
            return new QuarantineResolutionResult(ResolutionDisposition.APPROVAL_REQUIRED, null);
        }
    }

    /** Durable state of a checker decision for one exact discard claim. */
    public enum DiscardApprovalState {
        /** The independent checker decision is live and has not authorized a mutation yet. */
        APPROVED,
        /** One exact discard atomically consumed the checker decision. */
        CONSUMED
    }

    /** Stable result vocabulary for idempotent discard approvals. */
    public enum DiscardApprovalDisposition {
        /** A new checker approval and bound audit mutation committed. */
        APPROVED,
        /** The exact immutable approval was returned. */
        IDEMPOTENT_REPLAY,
        /** Maker and checker resolve to the same verified actor. */
        SELF_APPROVAL,
        /** The projected claim no longer exactly matches the database authority. */
        FENCE_REJECTED,
        /** The request ID already identifies a different approval intent. */
        IDEMPOTENCY_CONFLICT,
        /** Exact replay material expired, but its tombstone still forbids request-ID reuse. */
        REPLAY_WINDOW_EXPIRED,
        /** The run no longer has the exact checkpoint closure. */
        STALE_CHECKPOINT
    }

    /**
     * Token-free checker approval for one exact live quarantine claim.
     *
     * @param approvalId opaque approval identity
     * @param key exact quarantine identity
     * @param claimOwner verified maker identity projected by the claim
     * @param claimVersion exact maintenance generation
     * @param claimUntil exact claim deadline
     * @param approverId distinct verified checker identity
     * @param reasonCode rationale that a discard must repeat exactly
     * @param approvedAt database-clock decision time
     * @param approvalUntil database-clock deadline no later than the claim deadline
     * @param approvalFingerprint canonical immutable checker-decision fingerprint
     */
    public record DiscardApproval(
            String approvalId,
            QuarantineKey key,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            String approverId,
            String reasonCode,
            Instant approvedAt,
            Instant approvalUntil,
            String approvalFingerprint) {
        /** Validates a complete token-free checker decision. */
        public DiscardApproval {
            approvalId = required(approvalId, "approvalId", 36);
            key = Objects.requireNonNull(key, "key");
            claimOwner = required(claimOwner, "claimOwner", 255);
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            approverId = required(approverId, "approverId", 255);
            reasonCode = reason(reasonCode);
            approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
            approvalUntil = Objects.requireNonNull(approvalUntil, "approvalUntil");
            approvalFingerprint = required(
                    approvalFingerprint, "approvalFingerprint", 80);
            if (claimVersion <= 0 || claimOwner.equals(approverId)
                    || approvedAt.isAfter(approvalUntil)
                    || approvalUntil.isAfter(claimUntil)) {
                throw new IllegalArgumentException("Invalid worker quarantine discard approval");
            }
        }
    }

    /** Idempotent result for one checker approval command. */
    public record DiscardApprovalResult(
            DiscardApprovalDisposition disposition, DiscardApproval approval) {
        /** Ensures only successful outcomes expose approval evidence. */
        public DiscardApprovalResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesApproval = disposition == DiscardApprovalDisposition.APPROVED
                    || disposition == DiscardApprovalDisposition.IDEMPOTENT_REPLAY;
            if (carriesApproval != (approval != null)) {
                throw new IllegalArgumentException("Invalid discard approval result");
            }
        }

        private static DiscardApprovalResult approved(DiscardApproval approval) {
            return new DiscardApprovalResult(DiscardApprovalDisposition.APPROVED, approval);
        }

        private static DiscardApprovalResult replay(DiscardApproval approval) {
            return new DiscardApprovalResult(
                    DiscardApprovalDisposition.IDEMPOTENT_REPLAY, approval);
        }

        private static DiscardApprovalResult selfApproval() {
            return new DiscardApprovalResult(DiscardApprovalDisposition.SELF_APPROVAL, null);
        }

        private static DiscardApprovalResult fenceRejected() {
            return new DiscardApprovalResult(DiscardApprovalDisposition.FENCE_REJECTED, null);
        }

        private static DiscardApprovalResult conflict() {
            return new DiscardApprovalResult(
                    DiscardApprovalDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        private static DiscardApprovalResult replayWindowExpired() {
            return new DiscardApprovalResult(
                    DiscardApprovalDisposition.REPLAY_WINDOW_EXPIRED, null);
        }

        private static DiscardApprovalResult staleCheckpoint() {
            return new DiscardApprovalResult(
                    DiscardApprovalDisposition.STALE_CHECKPOINT, null);
        }
    }

    /** Stable result vocabulary for an approved discard command. */
    public enum ApprovedDiscardDisposition {
        /** A new discard, approval consumption, history, and audit mutation committed. */
        DISCARDED,
        /** The exact immutable discard receipt was returned. */
        IDEMPOTENT_REPLAY,
        /** The maker claim token, version, expiry, or quarantine fence failed. */
        FENCE_REJECTED,
        /** The checker approval is absent, expired, consumed, self-issued, or mismatched. */
        APPROVAL_REJECTED,
        /** The request ID already identifies a different discard intent. */
        IDEMPOTENCY_CONFLICT,
        /** Exact replay material expired, but its tombstone still forbids request-ID reuse. */
        REPLAY_WINDOW_EXPIRED,
        /** The run no longer has the exact checkpoint closure. */
        STALE_CHECKPOINT
    }

    /**
     * Immutable token-free receipt proving an independently approved discard.
     *
     * @param key exact quarantine identity
     * @param ownerId verified maker that held the secret claim
     * @param approvalId consumed checker approval identity
     * @param approverId distinct verified checker
     * @param approvalFingerprint immutable checker-decision fingerprint
     * @param reasonCode exact shared rationale
     * @param version resulting maintenance generation
     * @param actedAt database-clock discard time
     * @param receiptFingerprint canonical two-person receipt fingerprint
     */
    public record ApprovedDiscardReceipt(
            QuarantineKey key,
            String ownerId,
            String approvalId,
            String approverId,
            String approvalFingerprint,
            String reasonCode,
            long version,
            Instant actedAt,
            String receiptFingerprint) {
        /** Validates complete two-person evidence without exposing the claim token. */
        public ApprovedDiscardReceipt {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "ownerId", 255);
            approvalId = required(approvalId, "approvalId", 36);
            approverId = required(approverId, "approverId", 255);
            approvalFingerprint = required(
                    approvalFingerprint, "approvalFingerprint", 80);
            reasonCode = reason(reasonCode);
            actedAt = Objects.requireNonNull(actedAt, "actedAt");
            receiptFingerprint = required(
                    receiptFingerprint, "receiptFingerprint", 80);
            if (version <= 0 || ownerId.equals(approverId)) {
                throw new IllegalArgumentException("Invalid approved discard receipt");
            }
        }
    }

    /** Idempotent result of one independently approved discard. */
    public record ApprovedDiscardResult(
            ApprovedDiscardDisposition disposition, ApprovedDiscardReceipt receipt) {
        /** Ensures only successful outcomes expose a token-free receipt. */
        public ApprovedDiscardResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesReceipt = disposition == ApprovedDiscardDisposition.DISCARDED
                    || disposition == ApprovedDiscardDisposition.IDEMPOTENT_REPLAY;
            if (carriesReceipt != (receipt != null)) {
                throw new IllegalArgumentException("Invalid approved discard result");
            }
        }

        private static ApprovedDiscardResult discarded(ApprovedDiscardReceipt receipt) {
            return new ApprovedDiscardResult(ApprovedDiscardDisposition.DISCARDED, receipt);
        }

        private static ApprovedDiscardResult replay(ApprovedDiscardReceipt receipt) {
            return new ApprovedDiscardResult(
                    ApprovedDiscardDisposition.IDEMPOTENT_REPLAY, receipt);
        }

        private static ApprovedDiscardResult fenceRejected() {
            return new ApprovedDiscardResult(ApprovedDiscardDisposition.FENCE_REJECTED, null);
        }

        private static ApprovedDiscardResult approvalRejected() {
            return new ApprovedDiscardResult(
                    ApprovedDiscardDisposition.APPROVAL_REJECTED, null);
        }

        private static ApprovedDiscardResult conflict() {
            return new ApprovedDiscardResult(
                    ApprovedDiscardDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        private static ApprovedDiscardResult replayWindowExpired() {
            return new ApprovedDiscardResult(
                    ApprovedDiscardDisposition.REPLAY_WINDOW_EXPIRED, null);
        }

        private static ApprovedDiscardResult staleCheckpoint() {
            return new ApprovedDiscardResult(
                    ApprovedDiscardDisposition.STALE_CHECKPOINT, null);
        }
    }

    /**
     * Immutable retained evidence for a two-person discard.
     *
     * @param historyId opaque history identity
     * @param key exact quarantine identity at discard time
     * @param quarantineReason automatic isolation reason
     * @param consecutiveFailures threshold-crossing count
     * @param quarantineThreshold policy threshold applied at isolation
     * @param firstObservedAt first same-reason observation
     * @param quarantinedAt automatic isolation time
     * @param reasonCode shared maker-checker rationale
     * @param ownerId verified maker identity
     * @param approvalId consumed approval identity
     * @param approverId distinct verified checker identity
     * @param approvalFingerprint immutable checker-decision fingerprint
     * @param version resulting maintenance generation
     * @param actedAt database-clock discard time
     * @param receiptFingerprint immutable two-person receipt fingerprint
     * @param recordFingerprint whole retained-record fingerprint
     */
    public record ApprovedDiscardHistoryRecord(
            String historyId,
            QuarantineKey key,
            WorkerCandidateDeferralReason quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String reasonCode,
            String ownerId,
            String approvalId,
            String approverId,
            String approvalFingerprint,
            long version,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        /** Validates complete token-free two-person history. */
        public ApprovedDiscardHistoryRecord {
            historyId = required(historyId, "historyId", 36);
            key = Objects.requireNonNull(key, "key");
            quarantineReason = Objects.requireNonNull(quarantineReason, "quarantineReason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt");
            reasonCode = reason(reasonCode);
            ownerId = required(ownerId, "ownerId", 255);
            approvalId = required(approvalId, "approvalId", 36);
            approverId = required(approverId, "approverId", 255);
            approvalFingerprint = required(
                    approvalFingerprint, "approvalFingerprint", 80);
            actedAt = Objects.requireNonNull(actedAt, "actedAt");
            receiptFingerprint = required(
                    receiptFingerprint, "receiptFingerprint", 80);
            recordFingerprint = required(recordFingerprint, "recordFingerprint", 80);
            if (quarantineThreshold < 1 || consecutiveFailures < quarantineThreshold
                    || firstObservedAt.isAfter(quarantinedAt) || version <= 0
                    || ownerId.equals(approverId)) {
                throw new IllegalArgumentException("Invalid approved discard history record");
            }
        }
    }

    /**
     * Immutable token-free historical evidence for one manual quarantine action.
     *
     * @param historyId opaque history identity
     * @param key exact quarantine identity at action time
     * @param quarantineReason automatic isolation reason
     * @param consecutiveFailures threshold-crossing count
     * @param quarantineThreshold policy threshold applied at isolation
     * @param firstObservedAt first same-reason observation
     * @param quarantinedAt isolation time
     * @param action manual release or discard
     * @param reasonCode bounded operational rationale
     * @param ownerId verified maintenance actor
     * @param version resulting maintenance generation
     * @param actedAt database-clock action time
     * @param receiptFingerprint immutable action receipt fingerprint
     * @param recordFingerprint whole retained-history record fingerprint
     */
    public record QuarantineHistoryRecord(
            String historyId,
            QuarantineKey key,
            WorkerCandidateDeferralReason quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            ResolutionAction action,
            String reasonCode,
            String ownerId,
            long version,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        /** Validates complete retained action evidence without any claim token. */
        public QuarantineHistoryRecord {
            historyId = required(historyId, "historyId", 36);
            key = Objects.requireNonNull(key, "key");
            quarantineReason = Objects.requireNonNull(quarantineReason, "quarantineReason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt");
            action = Objects.requireNonNull(action, "action");
            reasonCode = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
            ownerId = required(ownerId, "ownerId", 255);
            actedAt = Objects.requireNonNull(actedAt, "actedAt");
            receiptFingerprint = required(
                    receiptFingerprint, "receiptFingerprint", 80);
            recordFingerprint = required(recordFingerprint, "recordFingerprint", 80);
            if (quarantineThreshold < 1 || consecutiveFailures < quarantineThreshold
                    || firstObservedAt.isAfter(quarantinedAt)
                    || !REASON_CODE.matcher(reasonCode).matches() || version <= 0) {
                throw new IllegalArgumentException("Invalid worker quarantine history record");
            }
        }
    }

    /**
     * Payload-free, integrity-verified quarantine projection.
     *
     * @param runId durable execution identity
     * @param checkpointFingerprint exact poisoned checkpoint closure
     * @param reason closed automatic failure classification
     * @param consecutiveFailures observations that crossed the threshold
     * @param quarantineThreshold policy threshold in force at isolation time
     * @param firstObservedAt first same-reason database observation
     * @param quarantinedAt threshold-crossing database time
     * @param state current maintenance ownership projection
     * @param claimOwner verified live owner, blank when available
     * @param claimUntil database-clock claim deadline, epoch when available
     * @param version monotonic maintenance fence generation
     */
    public record QuarantineRecord(
            String runId,
            String checkpointFingerprint,
            WorkerCandidateDeferralReason reason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            QuarantineState state,
            String claimOwner,
            Instant claimUntil,
            long version) {
        /** Validates complete payload-free maintenance metadata. */
        public QuarantineRecord {
            runId = required(runId, "runId", 255);
            checkpointFingerprint = required(
                    checkpointFingerprint, "checkpointFingerprint", 80);
            reason = Objects.requireNonNull(reason, "reason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt");
            state = Objects.requireNonNull(state, "state");
            claimOwner = claimOwner == null ? "" : claimOwner.trim();
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            if (consecutiveFailures < quarantineThreshold || quarantineThreshold < 1
                    || firstObservedAt.isAfter(quarantinedAt) || version < 0
                    || (state == QuarantineState.AVAILABLE
                    && (!claimOwner.isBlank() || !claimUntil.equals(Instant.EPOCH)))
                    || (state == QuarantineState.CLAIMED
                    && (claimOwner.isBlank() || !claimUntil.isAfter(Instant.EPOCH)))) {
                throw new IllegalArgumentException("Invalid worker quarantine projection");
            }
        }
    }

    private record StoredDiscardApproval(
            String scopeKey,
            String clientRequestId,
            String approvalId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            String approverId,
            String reasonCode,
            Instant approvedAt,
            Instant approvalUntil,
            String approvalState,
            String consumedByRequestId,
            Instant consumedAt,
            String requestFingerprint,
            String approvalFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private DiscardApprovalState state() {
            return DiscardApprovalState.valueOf(approvalState);
        }

        private DiscardApproval external() {
            return new DiscardApproval(approvalId, key(), claimOwner, claimVersion, claimUntil,
                    approverId, reasonCode, approvedAt, approvalUntil, approvalFingerprint);
        }

        private StoredDiscardApproval withApprovalFingerprint(String fingerprint) {
            return new StoredDiscardApproval(scopeKey, clientRequestId, approvalId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    claimOwner, claimVersion, claimUntil, approverId, reasonCode, approvedAt,
                    approvalUntil, approvalState, consumedByRequestId, consumedAt,
                    requestFingerprint, fingerprint, recordFingerprint);
        }

        private StoredDiscardApproval withRecordFingerprint(String fingerprint) {
            return new StoredDiscardApproval(scopeKey, clientRequestId, approvalId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    claimOwner, claimVersion, claimUntil, approverId, reasonCode, approvedAt,
                    approvalUntil, approvalState, consumedByRequestId, consumedAt,
                    requestFingerprint, approvalFingerprint, fingerprint);
        }

        private StoredDiscardApproval consumed(String requestId, Instant at) {
            return new StoredDiscardApproval(scopeKey, clientRequestId, approvalId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    claimOwner, claimVersion, claimUntil, approverId, reasonCode, approvedAt,
                    approvalUntil, DiscardApprovalState.CONSUMED.name(), requestId, at,
                    requestFingerprint, approvalFingerprint, "");
        }
    }

    private record StoredApprovedDiscardCommand(
            String scopeKey,
            String clientRequestId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String resolutionOwner,
            long claimVersion,
            Instant claimUntil,
            String approvalId,
            String approverId,
            String reasonCode,
            String requestFingerprint,
            long resultVersion,
            Instant resultActedAt,
            String resultApprovalFingerprint,
            String resultReceiptFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private ApprovedDiscardReceipt receipt() {
            return new ApprovedDiscardReceipt(key(), resolutionOwner, approvalId, approverId,
                    resultApprovalFingerprint, reasonCode, resultVersion, resultActedAt,
                    resultReceiptFingerprint);
        }

        private StoredApprovedDiscardCommand withRecordFingerprint(String fingerprint) {
            return new StoredApprovedDiscardCommand(scopeKey, clientRequestId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    resolutionOwner, claimVersion, claimUntil, approvalId, approverId,
                    reasonCode, requestFingerprint, resultVersion, resultActedAt,
                    resultApprovalFingerprint, resultReceiptFingerprint, fingerprint);
        }
    }

    private record StoredApprovedDiscardHistory(
            String historyId,
            String scopeKey,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String reasonCode,
            String resolutionOwner,
            String approvalId,
            String approverId,
            String approvalFingerprint,
            long resultVersion,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private ApprovedDiscardHistoryRecord external() {
            return new ApprovedDiscardHistoryRecord(historyId, key(),
                    WorkerCandidateDeferralReason.valueOf(quarantineReason),
                    consecutiveFailures, quarantineThreshold, firstObservedAt, quarantinedAt,
                    reasonCode, resolutionOwner, approvalId, approverId, approvalFingerprint,
                    resultVersion, actedAt, receiptFingerprint, recordFingerprint);
        }

        private StoredApprovedDiscardHistory withRecordFingerprint(String fingerprint) {
            return new StoredApprovedDiscardHistory(historyId, scopeKey, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    quarantineReason, consecutiveFailures, quarantineThreshold, firstObservedAt,
                    quarantinedAt, reasonCode, resolutionOwner, approvalId, approverId,
                    approvalFingerprint, resultVersion, actedAt, receiptFingerprint,
                    fingerprint);
        }
    }

    private enum RequestKind {
        CLAIM,
        RESOLUTION,
        DISCARD_APPROVAL,
        APPROVED_DISCARD
    }

    record RetentionLease(String ownerId, String token, long epoch, Instant leaseUntil) {
    }

    private record RetentionState(
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            long totalTombstoned,
            long totalTombstonesPurged,
            long totalHistoryPurged,
            Instant lastSuccessAt,
            Instant updatedAt,
            String recordFingerprint) {
        private RetentionSnapshot snapshot(long tombstoneRecords) {
            return new RetentionSnapshot(leaseOwner, leaseEpoch, leaseUntil, revision,
                    totalTombstoned, totalTombstonesPurged, totalHistoryPurged,
                    tombstoneRecords, lastSuccessAt, updatedAt);
        }

        private RetentionState withFingerprint(String fingerprint) {
            return new RetentionState(leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    revision, totalTombstoned, totalTombstonesPurged, totalHistoryPurged,
                    lastSuccessAt, updatedAt, fingerprint);
        }
    }

    private record StoredRequestTombstone(
            RequestKind kind,
            String scopeKey,
            String requestKeyId,
            String requestKey,
            String requestFingerprint,
            String sourceRecordFingerprint,
            Instant sourceCompletedAt,
            Instant tombstonedAt,
            Instant expiresAt,
            int recordVersion,
            String recordFingerprint) {
        private StoredRequestTombstone withFingerprint(String fingerprint) {
            return new StoredRequestTombstone(kind, scopeKey, requestKeyId, requestKey,
                    requestFingerprint, sourceRecordFingerprint, sourceCompletedAt,
                    tombstonedAt, expiresAt, recordVersion, fingerprint);
        }
    }

    private record TombstoneKeyGeneration(int recordVersion, String keyId) {
    }

    private record StoredQuarantine(
            String scopeKey,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String reason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private ActiveWorkerCandidateQuarantine quarantine() {
            return new ActiveWorkerCandidateQuarantine(
                    WorkerCandidateDeferralReason.valueOf(reason), consecutiveFailures,
                    quarantineThreshold, firstObservedAt, quarantinedAt);
        }

        private QuarantineRecord external(
                QuarantineState state, String owner, Instant until, long version) {
            ActiveWorkerCandidateQuarantine active = quarantine();
            return new QuarantineRecord(runId, checkpointFingerprint, active.reason(),
                    active.consecutiveFailures(), active.quarantineThreshold(),
                    active.firstObservedAt(), active.quarantinedAt(), state, owner, until, version);
        }
    }

    private record StoredControl(
            String scopeKey,
            String runId,
            String checkpointFingerprint,
            String stateName,
            String claimOwner,
            String legacyClaimToken,
            String claimTokenKeyId,
            String claimTokenMac,
            Instant claimUntil,
            long version,
            int recordVersion,
            String recordFingerprint) {
        private QuarantineState state() {
            return QuarantineState.valueOf(stateName);
        }

        private StoredControl withActiveFence(String keyId, String mac) {
            return new StoredControl(scopeKey, runId, checkpointFingerprint, stateName,
                    claimOwner, "", keyId, mac, claimUntil, version,
                    CONTROL_RECORD_VERSION, recordFingerprint);
        }

        private StoredControl withFingerprint(String fingerprint) {
            return new StoredControl(scopeKey, runId, checkpointFingerprint, stateName,
                    claimOwner, legacyClaimToken, claimTokenKeyId, claimTokenMac,
                    claimUntil, version, recordVersion, fingerprint);
        }
    }

    private record StoredClaimCommand(
            String scopeKey,
            String clientRequestId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String claimOwner,
            long claimDurationSeconds,
            String requestFingerprint,
            String legacyResultClaimToken,
            String resultClaimTokenEnvelope,
            long resultVersion,
            Instant resultClaimUntil,
            Instant createdAt,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private StoredClaimCommand withProtectedToken(String envelope) {
            return new StoredClaimCommand(scopeKey, clientRequestId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    claimOwner, claimDurationSeconds, requestFingerprint, "", envelope,
                    resultVersion, resultClaimUntil, createdAt, recordFingerprint);
        }

        private StoredClaimCommand withFingerprint(String fingerprint) {
            return new StoredClaimCommand(scopeKey, clientRequestId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    claimOwner, claimDurationSeconds, requestFingerprint,
                    legacyResultClaimToken, resultClaimTokenEnvelope,
                    resultVersion, resultClaimUntil, createdAt, fingerprint);
        }
    }

    private record StoredResolutionCommand(
            String scopeKey,
            String clientRequestId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String resolutionOwner,
            String resolutionAction,
            String reasonCode,
            String requestFingerprint,
            long resultVersion,
            Instant resultActedAt,
            String resultReceiptFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private QuarantineResolutionReceipt receipt() {
            return new QuarantineResolutionReceipt(key(), resolutionOwner,
                    ResolutionAction.valueOf(resolutionAction), reasonCode, resultVersion,
                    resultActedAt, resultReceiptFingerprint);
        }

        private StoredResolutionCommand withFingerprint(String fingerprint) {
            return new StoredResolutionCommand(scopeKey, clientRequestId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    resolutionOwner, resolutionAction, reasonCode, requestFingerprint,
                    resultVersion, resultActedAt, resultReceiptFingerprint, fingerprint);
        }
    }

    private record StoredHistory(
            String historyId,
            String scopeKey,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String resolutionAction,
            String reasonCode,
            String resolutionOwner,
            long resultVersion,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private QuarantineHistoryRecord external() {
            return new QuarantineHistoryRecord(historyId, key(),
                    WorkerCandidateDeferralReason.valueOf(quarantineReason),
                    consecutiveFailures, quarantineThreshold, firstObservedAt, quarantinedAt,
                    ResolutionAction.valueOf(resolutionAction), reasonCode, resolutionOwner,
                    resultVersion, actedAt, receiptFingerprint, recordFingerprint);
        }

        private StoredHistory withFingerprint(String fingerprint) {
            return new StoredHistory(historyId, scopeKey, tenantId, organizationId, projectId,
                    environmentId, runId, checkpointFingerprint, quarantineReason,
                    consecutiveFailures, quarantineThreshold, firstObservedAt, quarantinedAt,
                    resolutionAction, reasonCode, resolutionOwner, resultVersion, actedAt,
                    receiptFingerprint, fingerprint);
        }
    }

    private static String required(String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is required and bounded");
        }
        return normalized;
    }
}
