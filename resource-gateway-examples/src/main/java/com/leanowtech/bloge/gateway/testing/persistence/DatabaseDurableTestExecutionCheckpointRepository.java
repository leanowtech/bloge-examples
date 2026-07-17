package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.INVALID_TRANSITION;
import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT;
import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.LEASE_ACTIVE;
import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.LEASE_EXPIRED;
import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.NOT_RESUMABLE;
import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE;
import static com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException.Reason.UNRECOGNIZED_DISPATCH;

/**
 * JDBC durable-test checkpoint store with transactional engine-state participation and CAS fencing.
 *
 * <p>The control row is content-addressed and redundantly indexes all authorization and fence facts.
 * Reads verify both the nested fingerprints and agreement between indexed columns and JSON. Advance
 * executes the engine mutation first and then performs owner/epoch/revision CAS; a losing writer is
 * rolled back as one transaction, including its engine-state writes.</p>
 */
public final class DatabaseDurableTestExecutionCheckpointRepository
        implements DurableTestExecutionCheckpointRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate repeatableReadScans;
    private final ObjectMapper objectMapper;
    private final DurableTestExecutionCheckpointIntegrity integrity;

    /**
     * Creates a transactional checkpoint authority over the isolated test-runtime database.
     *
     * @param jdbc JDBC facade for schema, verified reads, and transaction-participating writes
     * @param transactionManager local transaction manager shared with BLOGE staged stores
     * @param objectMapper mapper for complete immutable checkpoint JSON
     * @param integrity nested and aggregate checkpoint fingerprint authority
     */
    public DatabaseDurableTestExecutionCheckpointRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            DurableTestExecutionCheckpointIntegrity integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        PlatformTransactionManager requiredTransactionManager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(requiredTransactionManager);
        this.repeatableReadScans = new TransactionTemplate(requiredTransactionManager);
        this.repeatableReadScans.setReadOnly(true);
        this.repeatableReadScans.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the isolated durable control table and its scoped execution lookup index. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_creation_commands (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    authorization_fingerprint VARCHAR(80) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    actor_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL UNIQUE,
                    engine_execution_id VARCHAR(255) NOT NULL UNIQUE,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    rejection_code VARCHAR(128) NOT NULL,
                    result_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_json CLOB,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_execution_checkpoints (
                    run_id VARCHAR(255) PRIMARY KEY,
                    engine_execution_id VARCHAR(255) NOT NULL UNIQUE,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    actor_id VARCHAR(255) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    revision BIGINT NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    plan_fingerprint VARCHAR(80) NOT NULL,
                    target_kind VARCHAR(16),
                    target_id VARCHAR(255),
                    target_fingerprint VARCHAR(80),
                    fixture_fingerprint VARCHAR(80) NOT NULL,
                    fixture_state_fingerprint VARCHAR(80) NOT NULL,
                    provider_state_fingerprint VARCHAR(80) NOT NULL,
                    engine_state_fingerprint VARCHAR(80) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    checkpoint_json CLOB NOT NULL
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_execution_checkpoints
                ADD COLUMN IF NOT EXISTS target_kind VARCHAR(16)
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_execution_checkpoints
                ADD COLUMN IF NOT EXISTS target_id VARCHAR(255)
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_execution_checkpoints
                ADD COLUMN IF NOT EXISTS target_fingerprint VARCHAR(80)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_execution_scope_idx
                ON rg_test_durable_execution_checkpoints (
                    tenant_id, environment_id, engine_execution_id
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_execution_operations_idx
                ON rg_test_durable_execution_checkpoints (
                    status, lease_expires_at, updated_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_worker_candidate_idx
                ON rg_test_durable_execution_checkpoints (
                    tenant_id, environment_id, organization_id, project_id,
                    status, lease_expires_at, updated_at, run_id
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_creation_operations_idx
                ON rg_test_durable_creation_commands (
                    state, lease_expires_at, updated_at
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_resume_commands (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    expected_owner_id VARCHAR(255) NOT NULL,
                    expected_lease_epoch BIGINT NOT NULL,
                    expected_revision BIGINT NOT NULL,
                    expected_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    claimant_owner_id VARCHAR(255) NOT NULL,
                    lease_duration_seconds BIGINT NOT NULL,
                    authorization_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    result_dispatch_fingerprint VARCHAR(80) NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_json CLOB NOT NULL,
                    result_dispatch_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_resume_commands
                ADD COLUMN IF NOT EXISTS authorization_fingerprint VARCHAR(80)
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_resume_commands
                ADD COLUMN IF NOT EXISTS result_dispatch_fingerprint VARCHAR(80)
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_durable_resume_commands
                ADD COLUMN IF NOT EXISTS result_dispatch_json CLOB
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_dispatch_lookup_idx
                ON rg_test_durable_resume_commands (
                    tenant_id, environment_id, run_id, result_checkpoint_fingerprint
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_acquisitions (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    result_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    result_dispatch_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_json CLOB,
                    result_dispatch_json CLOB,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (
                        tenant_id, environment_id, organization_id, project_id,
                        client_request_id
                    )
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_worker_acquisition_operations_idx
                ON rg_test_durable_worker_acquisitions (outcome, observed_at)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_scan_cursor_locks (
                    scope_key VARCHAR(80) NOT NULL PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_scan_cursors (
                    scope_key VARCHAR(80) NOT NULL PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    cycle_epoch BIGINT NOT NULL,
                    cursor_lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    cursor_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    cursor_run_id VARCHAR(255) NOT NULL,
                    advanced_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    cursor_fingerprint VARCHAR(80) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_candidate_deferrals (
                    scope_key VARCHAR(80) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    retry_after TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, run_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_worker_deferral_slo_idx
                ON rg_test_durable_worker_candidate_deferrals (retry_after, reason)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_candidate_quarantines (
                    scope_key VARCHAR(80) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    quarantine_threshold INT NOT NULL,
                    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, run_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_worker_quarantine_slo_idx
                ON rg_test_durable_worker_candidate_quarantines (quarantined_at, reason)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_recovery_heartbeats (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    engine_execution_id VARCHAR(255) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    expected_revision BIGINT NOT NULL,
                    expected_lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expected_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    expected_dispatch_fingerprint VARCHAR(80) NOT NULL,
                    authorization_fingerprint VARCHAR(80) NOT NULL,
                    lease_duration_seconds BIGINT NOT NULL,
                    result_revision BIGINT NOT NULL,
                    result_lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    result_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    result_dispatch_fingerprint VARCHAR(80) NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_json CLOB NOT NULL,
                    result_dispatch_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, client_request_id),
                    CONSTRAINT uq_rg_test_durable_recovery_heartbeat_dispatch
                        UNIQUE (result_dispatch_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_heartbeat_dispatch_lookup_idx
                ON rg_test_durable_recovery_heartbeats (
                    tenant_id, environment_id, run_id, result_checkpoint_fingerprint
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_recovery_terminals (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    engine_execution_id VARCHAR(255) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    expected_revision BIGINT NOT NULL,
                    expected_lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expected_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    expected_dispatch_fingerprint VARCHAR(80) NOT NULL,
                    authorization_fingerprint VARCHAR(80) NOT NULL,
                    execution_outcome VARCHAR(32) NOT NULL,
                    terminal_engine_state_fingerprint VARCHAR(80) NOT NULL,
                    terminal_fixture_state_fingerprint VARCHAR(80) NOT NULL,
                    terminal_provider_state_fingerprint VARCHAR(80) NOT NULL,
                    evidence_gaps_fingerprint VARCHAR(80) NOT NULL,
                    result_revision BIGINT NOT NULL,
                    result_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    result_receipt_fingerprint VARCHAR(80) NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_json CLOB NOT NULL,
                    result_receipt_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, client_request_id),
                    CONSTRAINT uq_rg_test_durable_recovery_terminal_receipt
                        UNIQUE (result_receipt_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_terminal_result_lookup_idx
                ON rg_test_durable_recovery_terminals (
                    tenant_id, environment_id, run_id, result_checkpoint_fingerprint
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_recovery_steps (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    engine_execution_id VARCHAR(255) NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    expected_revision BIGINT NOT NULL,
                    expected_lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expected_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    expected_dispatch_fingerprint VARCHAR(80) NOT NULL,
                    authorization_fingerprint VARCHAR(80) NOT NULL,
                    boundary_outcome VARCHAR(32) NOT NULL,
                    engine_state_fingerprint VARCHAR(80) NOT NULL,
                    fixture_state_fingerprint VARCHAR(80) NOT NULL,
                    provider_state_fingerprint VARCHAR(80) NOT NULL,
                    evidence_gaps_fingerprint VARCHAR(80) NOT NULL,
                    evidence_gaps_json CLOB NOT NULL,
                    result_revision BIGINT NOT NULL,
                    result_checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    result_receipt_fingerprint VARCHAR(80) NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    result_checkpoint_json CLOB NOT NULL,
                    result_receipt_json CLOB,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, client_request_id),
                    CONSTRAINT uq_rg_test_durable_recovery_step_dispatch
                        UNIQUE (expected_dispatch_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_durable_recovery_step_result_idx
                ON rg_test_durable_recovery_steps (
                    tenant_id, environment_id, run_id, result_checkpoint_fingerprint
                )
                """);
    }

    @Override
    public InitialCreationReservationResult reserveInitialCreation(
            InitialCreationCommand command) {
        InitialCreationCommand requiredCommand = Objects.requireNonNull(command, "command");
        try {
            return transactions.execute(status -> reserveInitialCreationInTransaction(
                    requiredCommand));
        } catch (DataIntegrityViolationException concurrentInsert) {
            return transactions.execute(status -> {
                List<StoredInitialCreation> rows = findInitialCreations(
                        requiredCommand.scope().tenantId(),
                        requiredCommand.scope().environmentId(),
                        requiredCommand.clientRequestId(), true);
                if (rows.isEmpty()) {
                    throw concurrentInsert;
                }
                return resolveInitialCreation(requiredCommand, rows.getFirst(), databaseNow());
            });
        }
    }

    @Override
    public Optional<InitialCreationReservationResult> findInitialCreationResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint) {
        List<StoredInitialCreation> rows = findInitialCreations(
                normalized(tenantId), normalizedEnvironment(environmentId),
                normalized(clientRequestId), false);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredInitialCreation stored = rows.getFirst();
        requireValidInitialCreationRecord(stored);
        if (!stored.requestFingerprint().equals(normalized(requestFingerprint))) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different durable creation intent");
        }
        if (stored.state() == InitialCreationState.PENDING) {
            return Optional.empty();
        }
        return Optional.of(initialCreationResult(stored, false, true));
    }

    @Override
    public InitialCreationReservation heartbeatInitialCreation(
            InitialCreationReservation reservation,
            Duration leaseDuration) {
        InitialCreationReservation requiredReservation = Objects.requireNonNull(
                reservation, "reservation");
        Duration requiredLease = requireCreationLeaseDuration(leaseDuration);
        return transactions.execute(status -> {
            StoredInitialCreation stored = requireInitialCreation(requiredReservation, true);
            if (stored.state() != InitialCreationState.PENDING
                    || !stored.ownerId().equals(requiredReservation.ownerId())
                    || stored.leaseEpoch() != requiredReservation.leaseEpoch()
                    || !stored.recordFingerprint().equals(
                    requiredReservation.recordFingerprint())) {
                throw conflict(STALE_FENCE,
                        "Durable creation reservation changed before heartbeat");
            }
            Instant observedAt = databaseNow();
            requireLiveInitialCreation(stored, observedAt);
            if (!observedAt.isAfter(stored.updatedAt())) {
                throw new IllegalStateException(
                        "Test-runtime database clock did not advance for creation heartbeat");
            }
            Instant leaseExpiresAt = observedAt.plus(requiredLease);
            if (!leaseExpiresAt.isAfter(stored.leaseExpiresAt())) {
                throw conflict(INVALID_TRANSITION,
                        "Durable creation heartbeat must extend the live lease");
            }
            String recordFingerprint = initialCreationRecordFingerprint(
                    stored.scope(), stored.clientRequestId(), stored.requestFingerprint(),
                    stored.authorizationFingerprint(), stored.runId(),
                    stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                    stored.createdAt(), observedAt, leaseExpiresAt,
                    InitialCreationState.PENDING, "", "");
            StoredInitialCreation renewed = stored.renewed(
                    observedAt, leaseExpiresAt, recordFingerprint);
            if (updateInitialCreation(renewed, stored) != 1) {
                throw conflict(STALE_FENCE,
                        "Durable creation reservation changed concurrently");
            }
            return toInitialCreationReservation(renewed);
        });
    }

    @Override
    public InitialCreationReservationResult commitInitialCreation(
            InitialCreationReservation reservation,
            DurableTestExecutionCheckpoint checkpoint,
            BoundEngineStateMutation engineStateMutation,
            TestRuntimeTransactionMutation companionMutation) {
        InitialCreationReservation requiredReservation = Objects.requireNonNull(
                reservation, "reservation");
        DurableTestExecutionCheckpoint requiredCheckpoint = Objects.requireNonNull(
                checkpoint, "checkpoint");
        BoundEngineStateMutation requiredEngineMutation = Objects.requireNonNull(
                engineStateMutation, "engineStateMutation");
        TestRuntimeTransactionMutation requiredCompanion = Objects.requireNonNull(
                companionMutation, "companionMutation");
        requireSealed(requiredCheckpoint);
        requireMutationBinding(requiredCheckpoint, requiredEngineMutation);
        requireInitialCheckpointBinding(requiredReservation, requiredCheckpoint);
        return transactions.execute(status -> {
            StoredInitialCreation stored = requireInitialCreation(requiredReservation, true);
            if (stored.state() == InitialCreationState.COMMITTED) {
                requiredCompanion.apply(jdbc);
                return initialCreationResult(stored, false, true);
            }
            if (stored.state() != InitialCreationState.PENDING
                    || !stored.recordFingerprint().equals(
                    requiredReservation.recordFingerprint())) {
                throw conflict(STALE_FENCE,
                        "Durable creation reservation changed before commit");
            }
            Instant committedAt = databaseNow();
            requireLiveInitialCreation(stored, committedAt);
            insert(requiredCheckpoint);
            requiredEngineMutation.apply(jdbc);
            StoredInitialCreation committed = stored.committed(
                    requiredCheckpoint.checkpointFingerprint(), write(requiredCheckpoint),
                    committedAt, initialCreationRecordFingerprint(
                    stored.scope(), stored.clientRequestId(), stored.requestFingerprint(),
                    stored.authorizationFingerprint(), stored.runId(),
                    stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                    stored.createdAt(), committedAt, stored.leaseExpiresAt(),
                    InitialCreationState.COMMITTED, "",
                    requiredCheckpoint.checkpointFingerprint()));
            if (updateInitialCreation(committed, stored) != 1) {
                throw conflict(STALE_FENCE,
                        "Durable creation reservation changed concurrently");
            }
            requiredCompanion.apply(jdbc);
            return initialCreationResult(committed, false, false);
        });
    }

    @Override
    public InitialCreationReservationResult rejectInitialCreation(
            InitialCreationReservation reservation,
            String rejectionCode,
            TestRuntimeTransactionMutation companionMutation) {
        InitialCreationReservation requiredReservation = Objects.requireNonNull(
                reservation, "reservation");
        String requiredCode = normalized(rejectionCode).toUpperCase(java.util.Locale.ROOT);
        TestRuntimeTransactionMutation requiredCompanion = Objects.requireNonNull(
                companionMutation, "companionMutation");
        return transactions.execute(status -> {
            StoredInitialCreation stored = requireInitialCreation(requiredReservation, true);
            if (stored.state() == InitialCreationState.REJECTED) {
                if (!stored.rejectionCode().equals(requiredCode)) {
                    throw conflict(IDEMPOTENCY_CONFLICT,
                            "Durable creation already records a different rejection");
                }
                requiredCompanion.apply(jdbc);
                return initialCreationResult(stored, false, true);
            }
            if (stored.state() != InitialCreationState.PENDING
                    || !stored.recordFingerprint().equals(
                    requiredReservation.recordFingerprint())) {
                throw conflict(STALE_FENCE,
                        "Durable creation reservation changed before rejection");
            }
            Instant rejectedAt = databaseNow();
            requireLiveInitialCreation(stored, rejectedAt);
            String recordFingerprint = initialCreationRecordFingerprint(
                    stored.scope(), stored.clientRequestId(), stored.requestFingerprint(),
                    stored.authorizationFingerprint(), stored.runId(),
                    stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                    stored.createdAt(), rejectedAt, stored.leaseExpiresAt(),
                    InitialCreationState.REJECTED, requiredCode, "");
            StoredInitialCreation rejected = stored.rejected(
                    requiredCode, rejectedAt, recordFingerprint);
            toInitialCreationReservation(rejected);
            if (updateInitialCreation(rejected, stored) != 1) {
                throw conflict(STALE_FENCE,
                        "Durable creation reservation changed concurrently");
            }
            requiredCompanion.apply(jdbc);
            return initialCreationResult(rejected, false, false);
        });
    }

    private InitialCreationReservationResult reserveInitialCreationInTransaction(
            InitialCreationCommand command) {
        List<StoredInitialCreation> rows = findInitialCreations(
                command.scope().tenantId(), command.scope().environmentId(),
                command.clientRequestId(), true);
        Instant observedAt = databaseNow();
        if (rows.isEmpty()) {
            StoredInitialCreation created = newInitialCreation(command, observedAt);
            insertInitialCreation(created);
            return initialCreationResult(created, true, false);
        }
        return resolveInitialCreation(command, rows.getFirst(), observedAt);
    }

    private InitialCreationReservationResult resolveInitialCreation(
            InitialCreationCommand command,
            StoredInitialCreation stored,
            Instant observedAt) {
        requireValidInitialCreationRecord(stored);
        if (!stored.matches(command)) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different durable creation intent");
        }
        if (stored.state() != InitialCreationState.PENDING) {
            return initialCreationResult(stored, false, true);
        }
        if (stored.leaseExpiresAt().isAfter(observedAt)) {
            return initialCreationResult(stored, false, false);
        }
        if (stored.leaseEpoch() == Long.MAX_VALUE) {
            throw conflict(INVALID_TRANSITION,
                    "Durable creation reservation lease epoch cannot advance");
        }
        Instant leaseExpiresAt = observedAt.plus(command.leaseDuration());
        long leaseEpoch = stored.leaseEpoch() + 1;
        String recordFingerprint = initialCreationRecordFingerprint(
                stored.scope(), stored.clientRequestId(), stored.requestFingerprint(),
                stored.authorizationFingerprint(), stored.runId(), stored.engineExecutionId(),
                command.claimantOwnerId(), leaseEpoch, stored.createdAt(), observedAt,
                leaseExpiresAt, InitialCreationState.PENDING, "", "");
        StoredInitialCreation acquired = stored.acquired(
                command.claimantOwnerId(), leaseEpoch, observedAt, leaseExpiresAt,
                recordFingerprint);
        if (updateInitialCreation(acquired, stored) != 1) {
            throw conflict(STALE_FENCE,
                    "Durable creation reservation changed concurrently");
        }
        return initialCreationResult(acquired, true, false);
    }

    @Override
    public DurableTestExecutionCheckpoint create(
            DurableTestExecutionCheckpoint checkpoint,
            BoundEngineStateMutation engineStateMutation) {
        requireSealed(checkpoint);
        Objects.requireNonNull(engineStateMutation, "engineStateMutation");
        requireMutationBinding(checkpoint, engineStateMutation);
        if (checkpoint.lifecycle().revision() != 0) {
            throw conflict(INVALID_TRANSITION, "Initial durable checkpoint revision must be zero");
        }
        try {
            return transactions.execute(status -> {
                insert(checkpoint);
                engineStateMutation.apply(jdbc);
                return checkpoint;
            });
        } catch (DataIntegrityViolationException duplicate) {
            Optional<DurableTestExecutionCheckpoint> byRun = find(
                    checkpoint.scope().tenantId(), checkpoint.scope().environmentId(),
                    checkpoint.runId());
            if (byRun.isPresent()
                    && byRun.get().checkpointFingerprint().equals(checkpoint.checkpointFingerprint())) {
                return byRun.get();
            }
            Optional<DurableTestExecutionCheckpoint> byExecution = findByEngineExecutionId(
                    checkpoint.scope().tenantId(), checkpoint.scope().environmentId(),
                    checkpoint.engineExecutionId());
            if (byRun.isPresent() || byExecution.isPresent()
                    || durableIdentityExists(checkpoint.runId(), checkpoint.engineExecutionId())) {
                throw new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason.DUPLICATE_IDENTITY,
                        "Durable checkpoint identity already belongs to different immutable content");
            }
            throw duplicate;
        }
    }

    @Override
    public DurableTestExecutionCheckpoint advance(
            DurableTestExecutionCheckpoint checkpoint,
            Fence expectedFence,
            BoundEngineStateMutation engineStateMutation) {
        requireSealed(checkpoint);
        Objects.requireNonNull(expectedFence, "expectedFence");
        Objects.requireNonNull(engineStateMutation, "engineStateMutation");
        requireMutationBinding(checkpoint, engineStateMutation);
        return transactions.execute(status -> {
            DurableTestExecutionCheckpoint current = findInternal(
                    checkpoint.scope().tenantId(), checkpoint.scope().environmentId(),
                    checkpoint.runId()).orElseThrow(() -> conflict(
                    STALE_FENCE, "Durable checkpoint no longer exists in the expected scope"));
            requireTransition(current, checkpoint, expectedFence);
            engineStateMutation.apply(jdbc);
            int changed = update(checkpoint, current, expectedFence);
            if (changed != 1) {
                throw conflict(STALE_FENCE,
                        "Durable checkpoint owner, lease epoch, or revision changed concurrently");
            }
            return checkpoint;
        });
    }

    @Override
    public Optional<DurableTestExecutionCheckpoint> find(
            String tenantId, String environmentId, String runId) {
        return findInternal(normalized(tenantId), normalizedEnvironment(environmentId),
                normalized(runId));
    }

    @Override
    public Optional<DurableTestExecutionCheckpoint> findByEngineExecutionId(
            String tenantId, String environmentId, String engineExecutionId) {
        List<StoredRow> rows = jdbc.query(selectColumns() + """
                        WHERE tenant_id = ? AND environment_id = ? AND engine_execution_id = ?
                        """, this::mapRow, normalized(tenantId),
                normalizedEnvironment(environmentId), normalized(engineExecutionId));
        return rows.stream().findFirst().map(this::verifiedCheckpoint);
    }

    @Override
    public RecoveryCandidatePage findExpiredRecoveryCandidates(
            RecoveryCandidateQuery query) {
        RecoveryCandidateQuery requiredQuery = Objects.requireNonNull(query, "query");
        RecoveryCandidatePage page = repeatableReadScans.execute(
                status -> recoveryCandidatePage(requiredQuery));
        if (page == null) {
            throw new IllegalStateException(
                    "Durable recovery candidate transaction returned no result");
        }
        return page;
    }

    @Override
    public WorkerAcquisitionResult acquireWorkerCommandIdempotently(
            WorkerAcquisitionCommand command,
            Optional<WorkerAcquisitionSelection> selection,
            Optional<WorkerScanProgress> scanProgress,
            List<WorkerCandidateDeferral> deferrals,
            TestRuntimeTransactionMutation companionMutation) {
        WorkerAcquisitionCommand requiredCommand = Objects.requireNonNull(command, "command");
        Optional<WorkerAcquisitionSelection> requiredSelection = Objects.requireNonNull(
                selection, "selection");
        Optional<WorkerScanProgress> requiredProgress = Objects.requireNonNull(
                scanProgress, "scanProgress");
        List<WorkerCandidateDeferral> requiredDeferrals = List.copyOf(
                Objects.requireNonNull(deferrals, "deferrals"));
        TestRuntimeTransactionMutation requiredMutation = Objects.requireNonNull(
                companionMutation, "companionMutation");
        requiredProgress.ifPresent(progress -> {
            if (!requiredCommand.scope().equals(progress.scope())) {
                throw new IllegalArgumentException(
                        "Worker scan progress does not belong to its command scope");
            }
        });
        requiredSelection.ifPresent(value -> {
            value.authorization().requireValid(objectMapper);
            LeaseClaim claim = value.claim();
            WorkerAcquisitionScope scope = requiredCommand.scope();
            if (!scope.tenantId().equals(claim.tenantId())
                    || !scope.environmentId().equals(claim.environmentId())) {
                throw new IllegalArgumentException(
                        "Worker selection does not belong to its command scope");
            }
            if (requiredProgress.isEmpty()
                    || !claim.runId().equals(requiredProgress.orElseThrow().nextRunId())) {
                throw new IllegalArgumentException(
                        "Worker selection requires progress through the selected candidate");
            }
        });
        requireValidWorkerDeferrals(
                requiredCommand, requiredSelection, requiredProgress, requiredDeferrals);
        try {
            return transactions.execute(status -> {
                Optional<WorkerAcquisitionResult> existing = replayedWorkerAcquisition(
                        requiredCommand);
                if (existing.isPresent()) {
                    requiredMutation.apply(jdbc);
                    return existing.get();
                }
                Instant observedAt = databaseNow();
                DurableTestExecutionCheckpoint claimed = null;
                DurableTestRecoveryDispatch dispatch = null;
                WorkerAcquisitionOutcome outcome = WorkerAcquisitionOutcome.NO_WORK;
                if (requiredSelection.isPresent()) {
                    WorkerAcquisitionSelection selected = requiredSelection.get();
                    requireWorkerCandidateNotQuarantined(
                            requiredCommand.scope(), selected.claim());
                    claimed = claimExpiredLeaseAt(selected.claim(), observedAt);
                    if (!requiredCommand.scope().contains(claimed)) {
                        throw conflict(STALE_FENCE,
                                "Selected durable checkpoint left the worker authorization scope");
                    }
                    dispatch = DurableTestRecoveryDispatch.issue(
                            objectMapper, selected.authorization(), claimed);
                    outcome = WorkerAcquisitionOutcome.ACQUIRED;
                }
                boolean scanAdvanced = requiredProgress.map(progress -> advanceWorkerScanCursor(
                        requiredCommand.scope(), progress, observedAt)).orElse(false);
                if (scanAdvanced) {
                    persistWorkerCandidateDeferrals(
                            requiredCommand.scope(), requiredDeferrals, observedAt);
                }
                requiredSelection.ifPresent(value -> clearWorkerCandidateSchedulingState(
                        requiredCommand.scope(), value.claim().runId(),
                        value.claim().expectedCheckpointFingerprint()));
                StoredWorkerAcquisition stored = newWorkerAcquisition(
                        requiredCommand, outcome, observedAt, claimed, dispatch);
                insertWorkerAcquisition(stored);
                requiredMutation.apply(jdbc);
                return new WorkerAcquisitionResult(
                        outcome, observedAt, claimed, dispatch, false);
            });
        } catch (DataIntegrityViolationException concurrentCommand) {
            return transactions.execute(status -> {
                WorkerAcquisitionResult replay = replayedWorkerAcquisition(requiredCommand)
                        .orElseThrow(() -> concurrentCommand);
                requiredMutation.apply(jdbc);
                return replay;
            });
        }
    }

    @Override
    public Optional<WorkerAcquisitionResult> findWorkerAcquisitionResult(
            WorkerAcquisitionScope scope,
            String clientRequestId,
            String requestFingerprint) {
        WorkerAcquisitionCommand command = new WorkerAcquisitionCommand(
                clientRequestId, requestFingerprint, Objects.requireNonNull(scope, "scope"));
        return replayedWorkerAcquisition(command);
    }

    @Override
    public DurableTestExecutionCheckpoint claimExpiredLease(LeaseClaim claim) {
        LeaseClaim requiredClaim = Objects.requireNonNull(claim, "claim");
        return transactions.execute(status -> {
            Instant claimedAt = databaseNow();
            return claimExpiredLeaseAt(requiredClaim, claimedAt);
        });
    }

    @Override
    public LeaseClaimResult claimExpiredLeaseIdempotently(ResumeLeaseCommand command) {
        return claimExpiredLeaseIdempotently(command, TestRuntimeTransactionMutation.noop());
    }

    @Override
    public LeaseClaimResult claimExpiredLeaseIdempotently(
            ResumeLeaseCommand command,
            TestRuntimeTransactionMutation companionMutation) {
        ResumeLeaseCommand requiredCommand = Objects.requireNonNull(command, "command");
        requiredCommand.authorization().requireValid(objectMapper);
        TestRuntimeTransactionMutation requiredMutation = Objects.requireNonNull(
                companionMutation, "companionMutation");
        try {
            return transactions.execute(status -> {
                Optional<LeaseClaimResult> existing = replayedCommand(requiredCommand);
                if (existing.isPresent()) {
                    requiredMutation.apply(jdbc);
                    return existing.get();
                }
                Instant claimedAt = databaseNow();
                DurableTestExecutionCheckpoint claimed;
                try {
                    claimed = claimExpiredLeaseAt(requiredCommand.claim(), claimedAt);
                } catch (DurableTestExecutionCheckpointConflictException stale) {
                    if (stale.reason() != STALE_FENCE) {
                        throw stale;
                    }
                    Optional<LeaseClaimResult> committed = replayedCommand(requiredCommand);
                    if (committed.isPresent()) {
                        requiredMutation.apply(jdbc);
                        return committed.get();
                    }
                    throw stale;
                }
                DurableTestRecoveryDispatch dispatch = DurableTestRecoveryDispatch.issue(
                        objectMapper, requiredCommand.authorization(), claimed);
                insertResumeCommand(requiredCommand, claimed, dispatch, claimedAt);
                LeaseClaimResult result = new LeaseClaimResult(claimed, dispatch, false);
                requiredMutation.apply(jdbc);
                return result;
            });
        } catch (DataIntegrityViolationException concurrentCommand) {
            return transactions.execute(status -> {
                LeaseClaimResult replay = replayedCommand(requiredCommand)
                        .orElseThrow(() -> concurrentCommand);
                requiredMutation.apply(jdbc);
                return replay;
            });
        }
    }

    @Override
    public Optional<LeaseClaimResult> findLeaseClaimResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint) {
        List<StoredResumeCommand> rows = findResumeCommands(
                normalized(tenantId), normalizedEnvironment(environmentId),
                normalized(clientRequestId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredResumeCommand stored = rows.getFirst();
        requireValidResumeCommandRecord(stored);
        if (!stored.requestFingerprint().equals(normalized(requestFingerprint))) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different durable resume intent");
        }
        return Optional.of(verifiedCommandResult(stored, true));
    }

    @Override
    public Optional<DurableTestRecoveryDispatch> findRecoveryDispatch(
            String tenantId,
            String environmentId,
            String runId,
            Fence expectedFence,
            String expectedCheckpointFingerprint) {
        Objects.requireNonNull(expectedFence, "expectedFence");
        Optional<DurableTestRecoveryDispatch> heartbeatDispatch = findHeartbeatDispatch(
                normalized(tenantId), normalizedEnvironment(environmentId), normalized(runId),
                expectedFence, normalized(expectedCheckpointFingerprint));
        if (heartbeatDispatch.isPresent()) {
            return heartbeatDispatch;
        }
        List<StoredResumeCommand> rows = jdbc.query(resumeCommandSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                          AND claimant_owner_id = ?
                          AND result_checkpoint_fingerprint = ?
                        """, this::mapResumeCommand, normalized(tenantId),
                normalizedEnvironment(environmentId), normalized(runId),
                expectedFence.ownerId(), normalized(expectedCheckpointFingerprint));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Durable recovery dispatch identity is not unique");
        }
        StoredResumeCommand stored = rows.getFirst();
        requireValidResumeCommandRecord(stored);
        LeaseClaimResult result = verifiedCommandResult(stored, true);
        DurableTestRecoveryDispatch dispatch = result.dispatch();
        if (dispatch.leaseEpoch() != expectedFence.leaseEpoch()
                || dispatch.revision() != expectedFence.revision()) {
            return Optional.empty();
        }
        return Optional.of(dispatch);
    }

    @Override
    public RecoveryHeartbeatResult heartbeatRecoveryLeaseIdempotently(
            RecoveryHeartbeatCommand command) {
        return heartbeatRecoveryLeaseIdempotently(
                command, TestRuntimeTransactionMutation.noop());
    }

    @Override
    public RecoveryHeartbeatResult heartbeatRecoveryLeaseIdempotently(
            RecoveryHeartbeatCommand command,
            TestRuntimeTransactionMutation companionMutation) {
        RecoveryHeartbeatCommand requiredCommand = Objects.requireNonNull(command, "command");
        requiredCommand.expectedDispatch().requireValid(objectMapper);
        TestRuntimeTransactionMutation requiredMutation = Objects.requireNonNull(
                companionMutation, "companionMutation");
        try {
            return transactions.execute(status -> {
                requireIssuedRecoveryDispatch(requiredCommand.expectedDispatch());
                Optional<RecoveryHeartbeatResult> existing = replayedHeartbeat(requiredCommand);
                if (existing.isPresent()) {
                    requiredMutation.apply(jdbc);
                    return existing.get();
                }
                try {
                    Instant observedAt = databaseNow();
                    DurableTestExecutionCheckpoint current = liveHeartbeatCheckpoint(
                            requiredCommand, observedAt);
                    DurableTestExecutionCheckpoint renewed = renewedCheckpoint(
                            current, requiredCommand.leaseDuration(), observedAt);
                    DurableTestRecoveryDispatch successor = DurableTestRecoveryDispatch.issue(
                            objectMapper, requiredCommand.expectedDispatch().authorization(),
                            renewed);
                    if (heartbeatUpdate(renewed, current, requiredCommand.expectedDispatch(),
                            observedAt) != 1) {
                        throw conflict(STALE_FENCE,
                                "Durable recovery dispatch changed concurrently");
                    }
                    insertRecoveryHeartbeat(requiredCommand, renewed, successor, observedAt);
                    RecoveryHeartbeatResult result = new RecoveryHeartbeatResult(
                            renewed, successor, false);
                    requiredMutation.apply(jdbc);
                    return result;
                } catch (DurableTestExecutionCheckpointConflictException contested) {
                    if (contested.reason() != STALE_FENCE
                            && contested.reason() != LEASE_EXPIRED) {
                        throw contested;
                    }
                    Optional<RecoveryHeartbeatResult> committed =
                            replayedHeartbeat(requiredCommand);
                    if (committed.isPresent()) {
                        requiredMutation.apply(jdbc);
                        return committed.get();
                    }
                    throw contested;
                }
            });
        } catch (DataIntegrityViolationException concurrentCommand) {
            return transactions.execute(status -> {
                requireIssuedRecoveryDispatch(requiredCommand.expectedDispatch());
                RecoveryHeartbeatResult replay = replayedHeartbeat(requiredCommand)
                        .orElseThrow(() -> concurrentCommand);
                requiredMutation.apply(jdbc);
                return replay;
            });
        }
    }

    @Override
    public RecoveryTerminalResult terminalizeRecoveryIdempotently(
            RecoveryTerminalCommand command,
            BoundEngineStateMutation engineStateMutation) {
        return terminalizeRecoveryIdempotently(
                command, engineStateMutation, TestRuntimeTransactionMutation.noop());
    }

    @Override
    public RecoveryTerminalResult terminalizeRecoveryIdempotently(
            RecoveryTerminalCommand command,
            BoundEngineStateMutation engineStateMutation,
            TestRuntimeTransactionMutation companionMutation) {
        RecoveryTerminalCommand requiredCommand = Objects.requireNonNull(command, "command");
        requiredCommand.expectedDispatch().requireValid(objectMapper);
        BoundEngineStateMutation requiredEngineMutation = Objects.requireNonNull(
                engineStateMutation, "engineStateMutation");
        requireTerminalMutationBinding(requiredCommand, requiredEngineMutation);
        TestRuntimeTransactionMutation requiredCompanion = Objects.requireNonNull(
                companionMutation, "companionMutation");
        try {
            return transactions.execute(status -> {
                requireIssuedRecoveryDispatch(requiredCommand.expectedDispatch());
                Optional<RecoveryTerminalResult> existing = replayedTerminal(requiredCommand);
                if (existing.isPresent()) {
                    requiredCompanion.apply(jdbc);
                    return existing.get();
                }
                try {
                    Instant observedAt = databaseNow();
                    DurableTestExecutionCheckpoint current = liveRecoveryCheckpoint(
                            requiredCommand.expectedDispatch(), observedAt,
                            "before its terminal transition");
                    DurableTestExecutionCheckpoint terminal = terminalCheckpoint(
                            current, requiredCommand, observedAt);
                    Fence sourceFence = new Fence(
                            current.lifecycle().ownerId(), current.lifecycle().leaseEpoch(),
                            current.lifecycle().revision());
                    requireTransition(current, terminal, sourceFence);
                    requireMutationBinding(terminal, requiredEngineMutation);
                    requiredEngineMutation.apply(jdbc);
                    if (terminalUpdate(terminal, current,
                            requiredCommand.expectedDispatch(), observedAt) != 1) {
                        throw conflict(STALE_FENCE,
                                "Durable recovery dispatch changed concurrently");
                    }
                    DurableTestRecoveryTerminalReceipt receipt =
                            DurableTestRecoveryTerminalReceipt.issue(
                                    objectMapper, requiredCommand.expectedDispatch(), terminal,
                                    requiredCommand.executionOutcome(),
                                    requiredCommand.evidenceGapCodes());
                    insertRecoveryTerminal(requiredCommand, terminal, receipt, observedAt);
                    RecoveryTerminalResult result = new RecoveryTerminalResult(
                            terminal, receipt, false);
                    requiredCompanion.apply(jdbc);
                    return result;
                } catch (DurableTestExecutionCheckpointConflictException contested) {
                    if (contested.reason() != STALE_FENCE
                            && contested.reason() != LEASE_EXPIRED) {
                        throw contested;
                    }
                    Optional<RecoveryTerminalResult> committed =
                            replayedTerminal(requiredCommand);
                    if (committed.isPresent()) {
                        requiredCompanion.apply(jdbc);
                        return committed.get();
                    }
                    throw contested;
                }
            });
        } catch (DataIntegrityViolationException concurrentCommand) {
            return transactions.execute(status -> {
                requireIssuedRecoveryDispatch(requiredCommand.expectedDispatch());
                RecoveryTerminalResult replay = replayedTerminal(requiredCommand)
                        .orElseThrow(() -> concurrentCommand);
                requiredCompanion.apply(jdbc);
                return replay;
            });
        }
    }

    @Override
    public Optional<RecoveryTerminalResult> findRecoveryTerminalResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint) {
        List<StoredRecoveryTerminal> rows = findRecoveryTerminals(
                normalized(tenantId), normalizedEnvironment(environmentId),
                normalized(clientRequestId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredRecoveryTerminal stored = rows.getFirst();
        requireValidRecoveryTerminalRecord(stored);
        if (!stored.requestFingerprint().equals(normalized(requestFingerprint))) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different recovery terminal intent");
        }
        return Optional.of(verifiedRecoveryTerminalResult(stored, true));
    }

    @Override
    public RecoveryStepResult advanceRecoveryStepIdempotently(
            RecoveryStepCommand command,
            BoundEngineStateMutation engineStateMutation,
            TestRuntimeTransactionMutation companionMutation) {
        RecoveryStepCommand requiredCommand = Objects.requireNonNull(command, "command");
        requiredCommand.expectedDispatch().requireValid(objectMapper);
        BoundEngineStateMutation requiredEngineMutation = Objects.requireNonNull(
                engineStateMutation, "engineStateMutation");
        requireRecoveryStepMutationBinding(requiredCommand, requiredEngineMutation);
        TestRuntimeTransactionMutation requiredCompanion = Objects.requireNonNull(
                companionMutation, "companionMutation");
        try {
            return transactions.execute(status -> {
                requireIssuedRecoveryDispatch(requiredCommand.expectedDispatch());
                Optional<RecoveryStepResult> existing = replayedRecoveryStep(requiredCommand);
                if (existing.isPresent()) {
                    requiredCompanion.apply(jdbc);
                    return existing.get();
                }
                try {
                    Instant observedAt = databaseNow();
                    DurableTestExecutionCheckpoint current = liveRecoveryCheckpoint(
                            requiredCommand.expectedDispatch(), observedAt,
                            "before its recovery step");
                    DurableTestExecutionCheckpoint next = recoveryStepCheckpoint(
                            current, requiredCommand, observedAt);
                    Fence sourceFence = new Fence(
                            current.lifecycle().ownerId(), current.lifecycle().leaseEpoch(),
                            current.lifecycle().revision());
                    requireTransition(current, next, sourceFence);
                    requireMutationBinding(next, requiredEngineMutation);
                    requiredEngineMutation.apply(jdbc);
                    if (recoveryStepUpdate(next, current,
                            requiredCommand.expectedDispatch(), observedAt) != 1) {
                        throw conflict(STALE_FENCE,
                                "Durable recovery dispatch changed concurrently");
                    }
                    DurableTestRecoveryTerminalReceipt receipt = requiredCommand.outcome().terminal()
                            ? DurableTestRecoveryTerminalReceipt.issue(
                            objectMapper, requiredCommand.expectedDispatch(), next,
                            requiredCommand.outcome().terminalOutcome(),
                            requiredCommand.evidenceGapCodes())
                            : null;
                    insertRecoveryStep(requiredCommand, next, receipt, observedAt);
                    RecoveryStepResult result = new RecoveryStepResult(
                            requiredCommand.outcome(), next, receipt, false);
                    requiredCompanion.apply(jdbc);
                    return result;
                } catch (DurableTestExecutionCheckpointConflictException contested) {
                    if (contested.reason() != STALE_FENCE
                            && contested.reason() != LEASE_EXPIRED) {
                        throw contested;
                    }
                    Optional<RecoveryStepResult> committed =
                            replayedRecoveryStep(requiredCommand);
                    if (committed.isPresent()) {
                        requiredCompanion.apply(jdbc);
                        return committed.get();
                    }
                    throw contested;
                }
            });
        } catch (DataIntegrityViolationException concurrentCommand) {
            return transactions.execute(status -> {
                requireIssuedRecoveryDispatch(requiredCommand.expectedDispatch());
                RecoveryStepResult replay = replayedRecoveryStep(requiredCommand)
                        .orElseThrow(() -> concurrentCommand);
                requiredCompanion.apply(jdbc);
                return replay;
            });
        }
    }

    @Override
    public Optional<RecoveryStepResult> findRecoveryStepResult(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint) {
        List<StoredRecoveryStep> rows = findRecoverySteps(
                normalized(tenantId), normalizedEnvironment(environmentId),
                normalized(clientRequestId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredRecoveryStep stored = rows.getFirst();
        requireValidRecoveryStepRecord(stored);
        if (!stored.requestFingerprint().equals(normalized(requestFingerprint))) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different recovery-step intent");
        }
        return Optional.of(verifiedRecoveryStepResult(stored, true));
    }

    private Optional<RecoveryStepResult> replayedRecoveryStep(
            RecoveryStepCommand command) {
        DurableTestRecoveryDispatch dispatch = command.expectedDispatch();
        List<StoredRecoveryStep> rows = findRecoverySteps(
                dispatch.scope().tenantId(), dispatch.scope().environmentId(),
                command.clientRequestId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredRecoveryStep stored = rows.getFirst();
        requireValidRecoveryStepRecord(stored);
        if (!stored.matches(command, evidenceGapsFingerprint(command.evidenceGapCodes()))) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different recovery-step intent");
        }
        return Optional.of(verifiedRecoveryStepResult(stored, true));
    }

    private List<StoredRecoveryStep> findRecoverySteps(
            String tenantId, String environmentId, String clientRequestId) {
        return jdbc.query(recoveryStepSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                        """, this::mapRecoveryStep, tenantId, environmentId, clientRequestId);
    }

    private static String recoveryStepSelect() {
        return """
                SELECT tenant_id, environment_id, client_request_id, request_fingerprint,
                       run_id, engine_execution_id, owner_id, lease_epoch, expected_revision,
                       expected_lease_expires_at, expected_checkpoint_fingerprint,
                       expected_dispatch_fingerprint, authorization_fingerprint,
                       boundary_outcome, engine_state_fingerprint, fixture_state_fingerprint,
                       provider_state_fingerprint, evidence_gaps_fingerprint,
                       evidence_gaps_json, result_revision,
                       result_checkpoint_fingerprint, result_receipt_fingerprint,
                       record_fingerprint, result_checkpoint_json, result_receipt_json, created_at
                FROM rg_test_durable_recovery_steps
                """;
    }

    private DurableTestExecutionCheckpoint recoveryStepCheckpoint(
            DurableTestExecutionCheckpoint current,
            RecoveryStepCommand command,
            Instant observedAt) {
        var lifecycle = current.lifecycle();
        if (observedAt.isBefore(lifecycle.updatedAt())) {
            throw new IllegalStateException(
                    "Test-runtime database clock moved behind the durable checkpoint");
        }
        boolean terminal = command.outcome().terminal();
        Instant resultingLease = terminal ? lifecycle.leaseExpiresAt() : observedAt;
        return integrity.seal(new DurableTestExecutionCheckpoint(
                current.schemaVersion(), current.scope(), current.runId(),
                current.engineExecutionId(), current.dependencies(),
                command.fixtureConsumptionState(), command.executionServiceState(),
                command.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                terminal ? DurableTestExecutionCheckpoint.Status.TERMINAL
                        : DurableTestExecutionCheckpoint.Status.SUSPENDED,
                lifecycle.ownerId(), lifecycle.leaseEpoch(), lifecycle.revision() + 1,
                lifecycle.createdAt(), observedAt, resultingLease), ""));
    }

    private int recoveryStepUpdate(
            DurableTestExecutionCheckpoint next,
            DurableTestExecutionCheckpoint current,
            DurableTestRecoveryDispatch expectedDispatch,
            Instant observedAt) {
        var lifecycle = next.lifecycle();
        int updated = jdbc.update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET status = ?, revision = ?, lease_expires_at = ?,
                    fixture_state_fingerprint = ?, provider_state_fingerprint = ?,
                    engine_state_fingerprint = ?, checkpoint_fingerprint = ?,
                    updated_at = ?, checkpoint_json = ?
                WHERE run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND engine_execution_id = ? AND status = 'RESUMING'
                  AND owner_id = ? AND lease_epoch = ? AND revision = ?
                  AND lease_expires_at = ? AND lease_expires_at > ?
                  AND checkpoint_fingerprint = ?
                """, lifecycle.status().name(), lifecycle.revision(),
                Timestamp.from(lifecycle.leaseExpiresAt()),
                next.fixtureConsumptionState().stateFingerprint(),
                next.executionServiceState().snapshotFingerprint(),
                next.engineState().closureFingerprint(), next.checkpointFingerprint(),
                Timestamp.from(lifecycle.updatedAt()), write(next), next.runId(),
                next.scope().tenantId(), next.scope().environmentId(), next.engineExecutionId(),
                lifecycle.ownerId(), lifecycle.leaseEpoch(), current.lifecycle().revision(),
                Timestamp.from(expectedDispatch.leaseExpiresAt()), Timestamp.from(observedAt),
                expectedDispatch.checkpointFingerprint());
        if (updated == 1) {
            clearWorkerCandidateSchedulingState(
                    new WorkerAcquisitionScope(
                            current.scope().tenantId(), current.scope().organizationId(),
                            current.scope().projectId(), current.scope().environmentId()),
                    current.runId(), current.checkpointFingerprint());
        }
        return updated;
    }

    private void insertRecoveryStep(
            RecoveryStepCommand command,
            DurableTestExecutionCheckpoint next,
            DurableTestRecoveryTerminalReceipt receipt,
            Instant createdAt) {
        DurableTestRecoveryDispatch source = command.expectedDispatch();
        String gapsFingerprint = evidenceGapsFingerprint(command.evidenceGapCodes());
        String receiptFingerprint = receipt == null ? "" : receipt.receiptFingerprint();
        String recordFingerprint = recoveryStepRecordFingerprint(
                command, next, receiptFingerprint, gapsFingerprint, createdAt);
        jdbc.update("""
                INSERT INTO rg_test_durable_recovery_steps (
                    tenant_id, environment_id, client_request_id, request_fingerprint,
                    run_id, engine_execution_id, owner_id, lease_epoch, expected_revision,
                    expected_lease_expires_at, expected_checkpoint_fingerprint,
                    expected_dispatch_fingerprint, authorization_fingerprint,
                    boundary_outcome, engine_state_fingerprint, fixture_state_fingerprint,
                    provider_state_fingerprint, evidence_gaps_fingerprint,
                    evidence_gaps_json, result_revision,
                    result_checkpoint_fingerprint, result_receipt_fingerprint,
                    record_fingerprint, result_checkpoint_json, result_receipt_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, source.scope().tenantId(), source.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(), source.runId(),
                source.engineExecutionId(), source.ownerId(), source.leaseEpoch(), source.revision(),
                Timestamp.from(source.leaseExpiresAt()), source.checkpointFingerprint(),
                source.dispatchFingerprint(), source.authorization().authorizationFingerprint(),
                command.outcome().name(), command.engineState().closureFingerprint(),
                command.fixtureConsumptionState().stateFingerprint(),
                command.executionServiceState().snapshotFingerprint(), gapsFingerprint,
                writeEvidenceGaps(command.evidenceGapCodes()), next.lifecycle().revision(),
                next.checkpointFingerprint(), receiptFingerprint,
                recordFingerprint, write(next),
                receipt == null ? null : writeTerminalReceipt(receipt),
                Timestamp.from(createdAt));
    }

    private RecoveryStepResult verifiedRecoveryStepResult(
            StoredRecoveryStep stored, boolean idempotentReplay) {
        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = objectMapper.readValue(
                    stored.resultCheckpointJson(), DurableTestExecutionCheckpoint.class);
            integrity.requireValid(checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery-step checkpoint is corrupt", corrupt);
        }
        RecoveryStepOutcome outcome;
        try {
            outcome = RecoveryStepOutcome.valueOf(stored.boundaryOutcome());
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored recovery-step outcome is corrupt", corrupt);
        }
        DurableTestRecoveryDispatch source;
        try {
            source = issuedRecoveryDispatch(
                    stored.tenantId(), stored.environmentId(), stored.runId(),
                    stored.expectedDispatchFingerprint());
            if (!stored.agreesWithSource(source)) {
                throw new IllegalArgumentException(
                        "Recovery-step source dispatch does not match its command record");
            }
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery-step source dispatch is corrupt", corrupt);
        }
        DurableTestRecoveryTerminalReceipt receipt = null;
        if (outcome.terminal()) {
            try {
                receipt = objectMapper.readValue(
                        stored.resultReceiptJson(), DurableTestRecoveryTerminalReceipt.class);
                receipt.requireValid(objectMapper, source, checkpoint);
            } catch (JsonProcessingException | IllegalArgumentException | NullPointerException corrupt) {
                throw new IllegalStateException(
                        "Stored recovery-step terminal receipt is corrupt", corrupt);
            }
        }
        List<String> evidenceGaps = readEvidenceGaps(stored.evidenceGapsJson());
        if (outcome.terminal() && !evidenceGaps.equals(receipt.evidenceGapCodes())) {
            throw new IllegalStateException(
                    "Stored recovery-step evidence gaps differ from the terminal receipt");
        }
        if (!stored.agreesWith(checkpoint, receipt,
                evidenceGapsFingerprint(evidenceGaps))) {
            throw new IllegalStateException("Stored recovery-step result is corrupt");
        }
        return new RecoveryStepResult(outcome, checkpoint, receipt, idempotentReplay);
    }

    private StoredRecoveryStep mapRecoveryStep(ResultSet rs, int rowNumber)
            throws SQLException {
        return new StoredRecoveryStep(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("client_request_id"), rs.getString("request_fingerprint"),
                rs.getString("run_id"), rs.getString("engine_execution_id"),
                rs.getString("owner_id"), rs.getLong("lease_epoch"),
                rs.getLong("expected_revision"),
                rs.getTimestamp("expected_lease_expires_at").toInstant(),
                rs.getString("expected_checkpoint_fingerprint"),
                rs.getString("expected_dispatch_fingerprint"),
                rs.getString("authorization_fingerprint"), rs.getString("boundary_outcome"),
                rs.getString("engine_state_fingerprint"),
                rs.getString("fixture_state_fingerprint"),
                rs.getString("provider_state_fingerprint"),
                rs.getString("evidence_gaps_fingerprint"), rs.getString("evidence_gaps_json"),
                rs.getLong("result_revision"),
                rs.getString("result_checkpoint_fingerprint"),
                rs.getString("result_receipt_fingerprint"), rs.getString("record_fingerprint"),
                rs.getString("result_checkpoint_json"), rs.getString("result_receipt_json"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void requireValidRecoveryStepRecord(StoredRecoveryStep stored) {
        String actual = ProtocolFingerprint.of(objectMapper, stored.fingerprintMaterial());
        if (!stored.recordFingerprint().equals(actual)) {
            throw new IllegalStateException("Stored recovery-step record is corrupt");
        }
    }

    private String recoveryStepRecordFingerprint(
            RecoveryStepCommand command,
            DurableTestExecutionCheckpoint next,
            String receiptFingerprint,
            String evidenceGapsFingerprint,
            Instant createdAt) {
        DurableTestRecoveryDispatch source = command.expectedDispatch();
        return ProtocolFingerprint.of(objectMapper, recoveryStepFingerprintMaterial(
                source.scope().tenantId(), source.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(), source.runId(),
                source.engineExecutionId(), source.ownerId(), source.leaseEpoch(), source.revision(),
                source.leaseExpiresAt(), source.checkpointFingerprint(),
                source.dispatchFingerprint(), source.authorization().authorizationFingerprint(),
                command.outcome().name(), command.engineState().closureFingerprint(),
                command.fixtureConsumptionState().stateFingerprint(),
                command.executionServiceState().snapshotFingerprint(), evidenceGapsFingerprint,
                next.lifecycle().revision(), next.checkpointFingerprint(), receiptFingerprint,
                createdAt));
    }

    private static Map<String, Object> recoveryStepFingerprintMaterial(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            long expectedRevision,
            Instant expectedLeaseExpiresAt,
            String expectedCheckpointFingerprint,
            String expectedDispatchFingerprint,
            String authorizationFingerprint,
            String boundaryOutcome,
            String engineStateFingerprint,
            String fixtureStateFingerprint,
            String providerStateFingerprint,
            String evidenceGapsFingerprint,
            long resultRevision,
            String resultCheckpointFingerprint,
            String resultReceiptFingerprint,
            Instant createdAt) {
        return Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableRecoveryStepCommandRecord.v1"),
                Map.entry("tenantId", tenantId),
                Map.entry("environmentId", environmentId),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("runId", runId),
                Map.entry("engineExecutionId", engineExecutionId),
                Map.entry("ownerId", ownerId),
                Map.entry("leaseEpoch", leaseEpoch),
                Map.entry("expectedRevision", expectedRevision),
                Map.entry("expectedLeaseExpiresAt", expectedLeaseExpiresAt),
                Map.entry("expectedCheckpointFingerprint", expectedCheckpointFingerprint),
                Map.entry("expectedDispatchFingerprint", expectedDispatchFingerprint),
                Map.entry("authorizationFingerprint", authorizationFingerprint),
                Map.entry("boundaryOutcome", boundaryOutcome),
                Map.entry("engineStateFingerprint", engineStateFingerprint),
                Map.entry("fixtureStateFingerprint", fixtureStateFingerprint),
                Map.entry("providerStateFingerprint", providerStateFingerprint),
                Map.entry("evidenceGapsFingerprint", evidenceGapsFingerprint),
                Map.entry("resultRevision", resultRevision),
                Map.entry("resultCheckpointFingerprint", resultCheckpointFingerprint),
                Map.entry("resultReceiptFingerprint", resultReceiptFingerprint),
                Map.entry("createdAt", createdAt));
    }

    private static void requireRecoveryStepMutationBinding(
            RecoveryStepCommand command, BoundEngineStateMutation mutation) {
        if (!command.expectedDispatch().engineExecutionId().equals(
                mutation.engineExecutionId())
                || !command.engineState().equals(mutation.engineState())) {
            throw conflict(INVALID_TRANSITION,
                    "Recovery-step intent does not match its engine-state mutation");
        }
    }

    private Optional<RecoveryTerminalResult> replayedTerminal(
            RecoveryTerminalCommand command) {
        DurableTestRecoveryDispatch dispatch = command.expectedDispatch();
        List<StoredRecoveryTerminal> rows = findRecoveryTerminals(
                dispatch.scope().tenantId(), dispatch.scope().environmentId(),
                command.clientRequestId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredRecoveryTerminal stored = rows.getFirst();
        requireValidRecoveryTerminalRecord(stored);
        if (!stored.matches(command, evidenceGapsFingerprint(command.evidenceGapCodes()))) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different recovery terminal intent");
        }
        return Optional.of(verifiedRecoveryTerminalResult(stored, true));
    }

    private List<StoredRecoveryTerminal> findRecoveryTerminals(
            String tenantId, String environmentId, String clientRequestId) {
        return jdbc.query(recoveryTerminalSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                        """, this::mapRecoveryTerminal, tenantId, environmentId,
                clientRequestId);
    }

    private static String recoveryTerminalSelect() {
        return """
                SELECT tenant_id, environment_id, client_request_id, request_fingerprint,
                       run_id, engine_execution_id, owner_id, lease_epoch, expected_revision,
                       expected_lease_expires_at, expected_checkpoint_fingerprint,
                       expected_dispatch_fingerprint, authorization_fingerprint,
                       execution_outcome, terminal_engine_state_fingerprint,
                       terminal_fixture_state_fingerprint, terminal_provider_state_fingerprint,
                       evidence_gaps_fingerprint, result_revision,
                       result_checkpoint_fingerprint, result_receipt_fingerprint,
                       record_fingerprint, result_checkpoint_json, result_receipt_json, created_at
                FROM rg_test_durable_recovery_terminals
                """;
    }

    private DurableTestExecutionCheckpoint terminalCheckpoint(
            DurableTestExecutionCheckpoint current,
            RecoveryTerminalCommand command,
            Instant observedAt) {
        var lifecycle = current.lifecycle();
        if (observedAt.isBefore(lifecycle.updatedAt())) {
            throw new IllegalStateException(
                    "Test-runtime database clock moved behind the durable checkpoint");
        }
        return integrity.seal(new DurableTestExecutionCheckpoint(
                current.schemaVersion(), current.scope(), current.runId(),
                current.engineExecutionId(), current.dependencies(),
                command.fixtureConsumptionState(), command.executionServiceState(),
                command.terminalEngineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                DurableTestExecutionCheckpoint.Status.TERMINAL, lifecycle.ownerId(),
                lifecycle.leaseEpoch(), lifecycle.revision() + 1, lifecycle.createdAt(),
                observedAt, lifecycle.leaseExpiresAt()), ""));
    }

    private int terminalUpdate(
            DurableTestExecutionCheckpoint terminal,
            DurableTestExecutionCheckpoint current,
            DurableTestRecoveryDispatch expectedDispatch,
            Instant observedAt) {
        var lifecycle = terminal.lifecycle();
        return jdbc.update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET status = ?, revision = ?, fixture_state_fingerprint = ?,
                    provider_state_fingerprint = ?, engine_state_fingerprint = ?,
                    checkpoint_fingerprint = ?, updated_at = ?, checkpoint_json = ?
                WHERE run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND engine_execution_id = ? AND status = 'RESUMING'
                  AND owner_id = ? AND lease_epoch = ? AND revision = ?
                  AND lease_expires_at = ? AND lease_expires_at > ?
                  AND checkpoint_fingerprint = ?
                """, lifecycle.status().name(), lifecycle.revision(),
                terminal.fixtureConsumptionState().stateFingerprint(),
                terminal.executionServiceState().snapshotFingerprint(),
                terminal.engineState().closureFingerprint(), terminal.checkpointFingerprint(),
                Timestamp.from(lifecycle.updatedAt()), write(terminal), terminal.runId(),
                terminal.scope().tenantId(), terminal.scope().environmentId(),
                terminal.engineExecutionId(), lifecycle.ownerId(), lifecycle.leaseEpoch(),
                current.lifecycle().revision(), Timestamp.from(expectedDispatch.leaseExpiresAt()),
                Timestamp.from(observedAt), expectedDispatch.checkpointFingerprint());
    }

    private void insertRecoveryTerminal(
            RecoveryTerminalCommand command,
            DurableTestExecutionCheckpoint terminal,
            DurableTestRecoveryTerminalReceipt receipt,
            Instant createdAt) {
        DurableTestRecoveryDispatch source = command.expectedDispatch();
        String gapsFingerprint = evidenceGapsFingerprint(command.evidenceGapCodes());
        String recordFingerprint = recoveryTerminalRecordFingerprint(
                command, terminal, receipt, gapsFingerprint, createdAt);
        jdbc.update("""
                INSERT INTO rg_test_durable_recovery_terminals (
                    tenant_id, environment_id, client_request_id, request_fingerprint,
                    run_id, engine_execution_id, owner_id, lease_epoch, expected_revision,
                    expected_lease_expires_at, expected_checkpoint_fingerprint,
                    expected_dispatch_fingerprint, authorization_fingerprint,
                    execution_outcome, terminal_engine_state_fingerprint,
                    terminal_fixture_state_fingerprint, terminal_provider_state_fingerprint,
                    evidence_gaps_fingerprint, result_revision,
                    result_checkpoint_fingerprint, result_receipt_fingerprint,
                    record_fingerprint, result_checkpoint_json, result_receipt_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, source.scope().tenantId(), source.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(), source.runId(),
                source.engineExecutionId(), source.ownerId(), source.leaseEpoch(), source.revision(),
                Timestamp.from(source.leaseExpiresAt()), source.checkpointFingerprint(),
                source.dispatchFingerprint(), source.authorization().authorizationFingerprint(),
                command.executionOutcome().name(),
                command.terminalEngineState().closureFingerprint(),
                command.fixtureConsumptionState().stateFingerprint(),
                command.executionServiceState().snapshotFingerprint(), gapsFingerprint,
                terminal.lifecycle().revision(), terminal.checkpointFingerprint(),
                receipt.receiptFingerprint(), recordFingerprint, write(terminal),
                writeTerminalReceipt(receipt), Timestamp.from(createdAt));
    }

    private RecoveryTerminalResult verifiedRecoveryTerminalResult(
            StoredRecoveryTerminal stored, boolean idempotentReplay) {
        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = objectMapper.readValue(
                    stored.resultCheckpointJson(), DurableTestExecutionCheckpoint.class);
            integrity.requireValid(checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery terminal checkpoint is corrupt", corrupt);
        }
        DurableTestRecoveryTerminalReceipt receipt;
        try {
            receipt = objectMapper.readValue(
                    stored.resultReceiptJson(), DurableTestRecoveryTerminalReceipt.class);
            DurableTestRecoveryDispatch source = issuedRecoveryDispatch(
                    stored.tenantId(), stored.environmentId(), stored.runId(),
                    stored.expectedDispatchFingerprint());
            receipt.requireValid(objectMapper, source, checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery terminal receipt is corrupt", corrupt);
        }
        if (!stored.agreesWith(checkpoint, receipt,
                evidenceGapsFingerprint(receipt.evidenceGapCodes()))) {
            throw new IllegalStateException("Stored recovery terminal result is corrupt");
        }
        return new RecoveryTerminalResult(checkpoint, receipt, idempotentReplay);
    }

    private StoredRecoveryTerminal mapRecoveryTerminal(ResultSet rs, int rowNumber)
            throws SQLException {
        return new StoredRecoveryTerminal(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("client_request_id"), rs.getString("request_fingerprint"),
                rs.getString("run_id"), rs.getString("engine_execution_id"),
                rs.getString("owner_id"), rs.getLong("lease_epoch"),
                rs.getLong("expected_revision"),
                rs.getTimestamp("expected_lease_expires_at").toInstant(),
                rs.getString("expected_checkpoint_fingerprint"),
                rs.getString("expected_dispatch_fingerprint"),
                rs.getString("authorization_fingerprint"),
                rs.getString("execution_outcome"),
                rs.getString("terminal_engine_state_fingerprint"),
                rs.getString("terminal_fixture_state_fingerprint"),
                rs.getString("terminal_provider_state_fingerprint"),
                rs.getString("evidence_gaps_fingerprint"), rs.getLong("result_revision"),
                rs.getString("result_checkpoint_fingerprint"),
                rs.getString("result_receipt_fingerprint"),
                rs.getString("record_fingerprint"), rs.getString("result_checkpoint_json"),
                rs.getString("result_receipt_json"), rs.getTimestamp("created_at").toInstant());
    }

    private void requireValidRecoveryTerminalRecord(StoredRecoveryTerminal stored) {
        String actual = ProtocolFingerprint.of(objectMapper, stored.fingerprintMaterial());
        if (!stored.recordFingerprint().equals(actual)) {
            throw new IllegalStateException("Stored recovery terminal record is corrupt");
        }
    }

    private String recoveryTerminalRecordFingerprint(
            RecoveryTerminalCommand command,
            DurableTestExecutionCheckpoint terminal,
            DurableTestRecoveryTerminalReceipt receipt,
            String evidenceGapsFingerprint,
            Instant createdAt) {
        DurableTestRecoveryDispatch source = command.expectedDispatch();
        return ProtocolFingerprint.of(objectMapper, recoveryTerminalFingerprintMaterial(
                source.scope().tenantId(), source.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(), source.runId(),
                source.engineExecutionId(), source.ownerId(), source.leaseEpoch(), source.revision(),
                source.leaseExpiresAt(), source.checkpointFingerprint(),
                source.dispatchFingerprint(), source.authorization().authorizationFingerprint(),
                command.executionOutcome().name(),
                command.terminalEngineState().closureFingerprint(),
                command.fixtureConsumptionState().stateFingerprint(),
                command.executionServiceState().snapshotFingerprint(), evidenceGapsFingerprint,
                terminal.lifecycle().revision(), terminal.checkpointFingerprint(),
                receipt.receiptFingerprint(), createdAt));
    }

    private static Map<String, Object> recoveryTerminalFingerprintMaterial(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            long expectedRevision,
            Instant expectedLeaseExpiresAt,
            String expectedCheckpointFingerprint,
            String expectedDispatchFingerprint,
            String authorizationFingerprint,
            String executionOutcome,
            String terminalEngineStateFingerprint,
            String terminalFixtureStateFingerprint,
            String terminalProviderStateFingerprint,
            String evidenceGapsFingerprint,
            long resultRevision,
            String resultCheckpointFingerprint,
            String resultReceiptFingerprint,
            Instant createdAt) {
        return Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableRecoveryTerminalCommandRecord.v1"),
                Map.entry("tenantId", tenantId),
                Map.entry("environmentId", environmentId),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("runId", runId),
                Map.entry("engineExecutionId", engineExecutionId),
                Map.entry("ownerId", ownerId),
                Map.entry("leaseEpoch", leaseEpoch),
                Map.entry("expectedRevision", expectedRevision),
                Map.entry("expectedLeaseExpiresAt", expectedLeaseExpiresAt),
                Map.entry("expectedCheckpointFingerprint", expectedCheckpointFingerprint),
                Map.entry("expectedDispatchFingerprint", expectedDispatchFingerprint),
                Map.entry("authorizationFingerprint", authorizationFingerprint),
                Map.entry("executionOutcome", executionOutcome),
                Map.entry("terminalEngineStateFingerprint", terminalEngineStateFingerprint),
                Map.entry("terminalFixtureStateFingerprint", terminalFixtureStateFingerprint),
                Map.entry("terminalProviderStateFingerprint", terminalProviderStateFingerprint),
                Map.entry("evidenceGapsFingerprint", evidenceGapsFingerprint),
                Map.entry("resultRevision", resultRevision),
                Map.entry("resultCheckpointFingerprint", resultCheckpointFingerprint),
                Map.entry("resultReceiptFingerprint", resultReceiptFingerprint),
                Map.entry("createdAt", createdAt));
    }

    private String evidenceGapsFingerprint(List<String> evidenceGapCodes) {
        return ProtocolFingerprint.of(objectMapper, evidenceGapCodes);
    }

    private static void requireTerminalMutationBinding(
            RecoveryTerminalCommand command,
            BoundEngineStateMutation mutation) {
        if (!command.expectedDispatch().engineExecutionId().equals(
                mutation.engineExecutionId())
                || !command.terminalEngineState().equals(mutation.engineState())) {
            throw conflict(INVALID_TRANSITION,
                    "Recovery terminal intent does not match its engine-state mutation");
        }
    }

    private void requireIssuedRecoveryDispatch(DurableTestRecoveryDispatch expected) {
        DurableTestRecoveryDispatch issued = issuedRecoveryDispatch(
                expected.scope().tenantId(), expected.scope().environmentId(), expected.runId(),
                expected.dispatchFingerprint());
        if (!issued.equals(expected)) {
            throw new IllegalStateException(
                    "Stored durable recovery dispatch issuance is corrupt");
        }
    }

    private DurableTestRecoveryDispatch issuedRecoveryDispatch(
            String tenantId,
            String environmentId,
            String runId,
            String dispatchFingerprint) {
        List<StoredRecoveryHeartbeat> heartbeatRows = jdbc.query(
                recoveryHeartbeatSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                          AND result_dispatch_fingerprint = ?
                        """, this::mapRecoveryHeartbeat, tenantId, environmentId, runId,
                dispatchFingerprint);
        List<StoredResumeCommand> claimRows = jdbc.query(resumeCommandSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                          AND result_dispatch_fingerprint = ?
                        """, this::mapResumeCommand, tenantId, environmentId, runId,
                dispatchFingerprint);
        if (heartbeatRows.size() + claimRows.size() != 1) {
            if (heartbeatRows.isEmpty() && claimRows.isEmpty()) {
                throw conflict(UNRECOGNIZED_DISPATCH,
                        "Durable recovery dispatch has no committed issuance record");
            }
            throw new IllegalStateException(
                    "Durable recovery dispatch issuance identity is not unique");
        }
        DurableTestRecoveryDispatch issued;
        if (!heartbeatRows.isEmpty()) {
            StoredRecoveryHeartbeat stored = heartbeatRows.getFirst();
            requireValidRecoveryHeartbeatRecord(stored);
            issued = verifiedRecoveryHeartbeatResult(stored, true).dispatch();
        } else {
            StoredResumeCommand stored = claimRows.getFirst();
            requireValidResumeCommandRecord(stored);
            issued = verifiedCommandResult(stored, true).dispatch();
        }
        return issued;
    }

    private Optional<DurableTestRecoveryDispatch> findHeartbeatDispatch(
            String tenantId,
            String environmentId,
            String runId,
            Fence expectedFence,
            String expectedCheckpointFingerprint) {
        List<StoredRecoveryHeartbeat> rows = jdbc.query(recoveryHeartbeatSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                          AND owner_id = ? AND result_checkpoint_fingerprint = ?
                        """, this::mapRecoveryHeartbeat, tenantId, environmentId, runId,
                expectedFence.ownerId(), expectedCheckpointFingerprint);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Durable recovery heartbeat dispatch identity is not unique");
        }
        StoredRecoveryHeartbeat stored = rows.getFirst();
        requireValidRecoveryHeartbeatRecord(stored);
        DurableTestRecoveryDispatch dispatch =
                verifiedRecoveryHeartbeatResult(stored, true).dispatch();
        if (dispatch.leaseEpoch() != expectedFence.leaseEpoch()
                || dispatch.revision() != expectedFence.revision()) {
            return Optional.empty();
        }
        return Optional.of(dispatch);
    }

    private Optional<RecoveryHeartbeatResult> replayedHeartbeat(
            RecoveryHeartbeatCommand command) {
        DurableTestRecoveryDispatch dispatch = command.expectedDispatch();
        List<StoredRecoveryHeartbeat> rows = findRecoveryHeartbeats(
                dispatch.scope().tenantId(), dispatch.scope().environmentId(),
                command.clientRequestId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredRecoveryHeartbeat stored = rows.getFirst();
        requireValidRecoveryHeartbeatRecord(stored);
        if (!stored.matches(command)) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different recovery heartbeat intent");
        }
        return Optional.of(verifiedRecoveryHeartbeatResult(stored, true));
    }

    private List<StoredRecoveryHeartbeat> findRecoveryHeartbeats(
            String tenantId, String environmentId, String clientRequestId) {
        return jdbc.query(recoveryHeartbeatSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                        """, this::mapRecoveryHeartbeat, tenantId, environmentId, clientRequestId);
    }

    private static String recoveryHeartbeatSelect() {
        return """
                SELECT tenant_id, environment_id, client_request_id, request_fingerprint,
                       run_id, engine_execution_id, owner_id, lease_epoch, expected_revision,
                       expected_lease_expires_at, expected_checkpoint_fingerprint,
                       expected_dispatch_fingerprint, authorization_fingerprint,
                       lease_duration_seconds, result_revision, result_lease_expires_at,
                       result_checkpoint_fingerprint, result_dispatch_fingerprint,
                       record_fingerprint, result_checkpoint_json, result_dispatch_json, created_at
                FROM rg_test_durable_recovery_heartbeats
                """;
    }

    private DurableTestExecutionCheckpoint liveHeartbeatCheckpoint(
            RecoveryHeartbeatCommand command, Instant observedAt) {
        return liveRecoveryCheckpoint(
                command.expectedDispatch(), observedAt, "before its heartbeat");
    }

    private DurableTestExecutionCheckpoint liveRecoveryCheckpoint(
            DurableTestRecoveryDispatch dispatch,
            Instant observedAt,
            String expiryContext) {
        DurableTestExecutionCheckpoint current = findInternal(
                dispatch.scope().tenantId(), dispatch.scope().environmentId(),
                dispatch.runId()).orElseThrow(() -> conflict(
                STALE_FENCE, "Durable recovery checkpoint no longer exists in its scope"));
        if (!dispatch.agreesWith(current)) {
            throw conflict(STALE_FENCE,
                    "Durable recovery dispatch no longer matches the live checkpoint");
        }
        if (!current.lifecycle().leaseExpiresAt().isAfter(observedAt)) {
            throw conflict(LEASE_EXPIRED,
                    "Durable recovery owner lease expired " + expiryContext);
        }
        if (current.lifecycle().revision() == Long.MAX_VALUE) {
            throw conflict(INVALID_TRANSITION,
                    "Durable recovery heartbeat revision cannot advance without overflow");
        }
        return current;
    }

    private DurableTestExecutionCheckpoint renewedCheckpoint(
            DurableTestExecutionCheckpoint current,
            Duration leaseDuration,
            Instant observedAt) {
        var lifecycle = current.lifecycle();
        Instant renewedUntil = observedAt.plus(leaseDuration);
        if (observedAt.isBefore(lifecycle.updatedAt())) {
            throw new IllegalStateException(
                    "Test-runtime database clock moved behind the durable checkpoint");
        }
        if (!renewedUntil.isAfter(lifecycle.leaseExpiresAt())) {
            throw conflict(INVALID_TRANSITION,
                    "Recovery heartbeat must extend the current lease deadline");
        }
        return integrity.seal(new DurableTestExecutionCheckpoint(
                current.schemaVersion(), current.scope(), current.runId(),
                current.engineExecutionId(), current.dependencies(),
                current.fixtureConsumptionState(), current.executionServiceState(),
                current.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                DurableTestExecutionCheckpoint.Status.RESUMING, lifecycle.ownerId(),
                lifecycle.leaseEpoch(), lifecycle.revision() + 1, lifecycle.createdAt(),
                observedAt, renewedUntil), ""));
    }

    private int heartbeatUpdate(
            DurableTestExecutionCheckpoint renewed,
            DurableTestExecutionCheckpoint current,
            DurableTestRecoveryDispatch expectedDispatch,
            Instant observedAt) {
        var lifecycle = renewed.lifecycle();
        return jdbc.update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET revision = ?, lease_expires_at = ?, checkpoint_fingerprint = ?,
                    updated_at = ?, checkpoint_json = ?
                WHERE run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND engine_execution_id = ? AND status = 'RESUMING'
                  AND owner_id = ? AND lease_epoch = ? AND revision = ?
                  AND lease_expires_at = ? AND lease_expires_at > ?
                  AND checkpoint_fingerprint = ?
                """, lifecycle.revision(), Timestamp.from(lifecycle.leaseExpiresAt()),
                renewed.checkpointFingerprint(), Timestamp.from(lifecycle.updatedAt()),
                write(renewed), renewed.runId(), renewed.scope().tenantId(),
                renewed.scope().environmentId(), renewed.engineExecutionId(),
                lifecycle.ownerId(), lifecycle.leaseEpoch(), current.lifecycle().revision(),
                Timestamp.from(expectedDispatch.leaseExpiresAt()), Timestamp.from(observedAt),
                expectedDispatch.checkpointFingerprint());
    }

    private void insertRecoveryHeartbeat(
            RecoveryHeartbeatCommand command,
            DurableTestExecutionCheckpoint result,
            DurableTestRecoveryDispatch successor,
            Instant createdAt) {
        DurableTestRecoveryDispatch source = command.expectedDispatch();
        String recordFingerprint = recoveryHeartbeatRecordFingerprint(
                command, result, successor, createdAt);
        jdbc.update("""
                INSERT INTO rg_test_durable_recovery_heartbeats (
                    tenant_id, environment_id, client_request_id, request_fingerprint,
                    run_id, engine_execution_id, owner_id, lease_epoch, expected_revision,
                    expected_lease_expires_at, expected_checkpoint_fingerprint,
                    expected_dispatch_fingerprint, authorization_fingerprint,
                    lease_duration_seconds, result_revision, result_lease_expires_at,
                    result_checkpoint_fingerprint, result_dispatch_fingerprint,
                    record_fingerprint, result_checkpoint_json, result_dispatch_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, source.scope().tenantId(), source.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(), source.runId(),
                source.engineExecutionId(), source.ownerId(), source.leaseEpoch(), source.revision(),
                Timestamp.from(source.leaseExpiresAt()), source.checkpointFingerprint(),
                source.dispatchFingerprint(), source.authorization().authorizationFingerprint(),
                command.leaseDuration().toSeconds(), result.lifecycle().revision(),
                Timestamp.from(result.lifecycle().leaseExpiresAt()),
                result.checkpointFingerprint(), successor.dispatchFingerprint(), recordFingerprint,
                write(result), writeDispatch(successor), Timestamp.from(createdAt));
    }

    private RecoveryHeartbeatResult verifiedRecoveryHeartbeatResult(
            StoredRecoveryHeartbeat stored, boolean idempotentReplay) {
        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = objectMapper.readValue(
                    stored.resultCheckpointJson(), DurableTestExecutionCheckpoint.class);
            integrity.requireValid(checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery heartbeat checkpoint is corrupt", corrupt);
        }
        DurableTestRecoveryDispatch dispatch;
        try {
            dispatch = objectMapper.readValue(
                    stored.resultDispatchJson(), DurableTestRecoveryDispatch.class);
            dispatch.requireValid(objectMapper, checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery heartbeat dispatch is corrupt", corrupt);
        }
        if (!stored.agreesWith(checkpoint, dispatch)) {
            throw new IllegalStateException("Stored recovery heartbeat result is corrupt");
        }
        return new RecoveryHeartbeatResult(checkpoint, dispatch, idempotentReplay);
    }

    private StoredRecoveryHeartbeat mapRecoveryHeartbeat(ResultSet rs, int rowNumber)
            throws SQLException {
        return new StoredRecoveryHeartbeat(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("client_request_id"), rs.getString("request_fingerprint"),
                rs.getString("run_id"), rs.getString("engine_execution_id"),
                rs.getString("owner_id"), rs.getLong("lease_epoch"),
                rs.getLong("expected_revision"),
                rs.getTimestamp("expected_lease_expires_at").toInstant(),
                rs.getString("expected_checkpoint_fingerprint"),
                rs.getString("expected_dispatch_fingerprint"),
                rs.getString("authorization_fingerprint"),
                rs.getLong("lease_duration_seconds"), rs.getLong("result_revision"),
                rs.getTimestamp("result_lease_expires_at").toInstant(),
                rs.getString("result_checkpoint_fingerprint"),
                rs.getString("result_dispatch_fingerprint"),
                rs.getString("record_fingerprint"), rs.getString("result_checkpoint_json"),
                rs.getString("result_dispatch_json"), rs.getTimestamp("created_at").toInstant());
    }

    private void requireValidRecoveryHeartbeatRecord(StoredRecoveryHeartbeat stored) {
        String actual = ProtocolFingerprint.of(objectMapper, stored.fingerprintMaterial());
        if (!stored.recordFingerprint().equals(actual)) {
            throw new IllegalStateException("Stored recovery heartbeat record is corrupt");
        }
    }

    private String recoveryHeartbeatRecordFingerprint(
            RecoveryHeartbeatCommand command,
            DurableTestExecutionCheckpoint result,
            DurableTestRecoveryDispatch successor,
            Instant createdAt) {
        DurableTestRecoveryDispatch source = command.expectedDispatch();
        return ProtocolFingerprint.of(objectMapper, recoveryHeartbeatFingerprintMaterial(
                source.scope().tenantId(), source.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(), source.runId(),
                source.engineExecutionId(), source.ownerId(), source.leaseEpoch(), source.revision(),
                source.leaseExpiresAt(), source.checkpointFingerprint(),
                source.dispatchFingerprint(), source.authorization().authorizationFingerprint(),
                command.leaseDuration().toSeconds(), result.lifecycle().revision(),
                result.lifecycle().leaseExpiresAt(), result.checkpointFingerprint(),
                successor.dispatchFingerprint(), createdAt));
    }

    private static Map<String, Object> recoveryHeartbeatFingerprintMaterial(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            long expectedRevision,
            Instant expectedLeaseExpiresAt,
            String expectedCheckpointFingerprint,
            String expectedDispatchFingerprint,
            String authorizationFingerprint,
            long leaseDurationSeconds,
            long resultRevision,
            Instant resultLeaseExpiresAt,
            String resultCheckpointFingerprint,
            String resultDispatchFingerprint,
            Instant createdAt) {
        return Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableRecoveryHeartbeatRecord.v1"),
                Map.entry("tenantId", tenantId),
                Map.entry("environmentId", environmentId),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("runId", runId),
                Map.entry("engineExecutionId", engineExecutionId),
                Map.entry("ownerId", ownerId),
                Map.entry("leaseEpoch", leaseEpoch),
                Map.entry("expectedRevision", expectedRevision),
                Map.entry("expectedLeaseExpiresAt", expectedLeaseExpiresAt),
                Map.entry("expectedCheckpointFingerprint", expectedCheckpointFingerprint),
                Map.entry("expectedDispatchFingerprint", expectedDispatchFingerprint),
                Map.entry("authorizationFingerprint", authorizationFingerprint),
                Map.entry("leaseDurationSeconds", leaseDurationSeconds),
                Map.entry("resultRevision", resultRevision),
                Map.entry("resultLeaseExpiresAt", resultLeaseExpiresAt),
                Map.entry("resultCheckpointFingerprint", resultCheckpointFingerprint),
                Map.entry("resultDispatchFingerprint", resultDispatchFingerprint),
                Map.entry("createdAt", createdAt));
    }

    private RecoveryCandidatePage recoveryCandidatePage(RecoveryCandidateQuery query) {
        WorkerAcquisitionScope scope = query.scope();
        WorkerScanCursorSnapshot cursor = findWorkerScanCursor(scope)
                .orElseGet(() -> emptyWorkerScanCursor(scope));
        Instant cutoff = databaseNow();
        List<RecoveryCandidate> candidates = new ArrayList<>(query.limit());
        if (cursor.position() == null) {
            appendRecoveryCandidates(candidates,
                    queryRecoveryCandidatesFromHead(scope, cutoff, query.limit()),
                    scope, cursor.cursorFingerprint(), cursor.cycleEpoch(), cutoff);
            return new RecoveryCandidatePage(candidates);
        }

        List<DurableTestExecutionCheckpoint> tail = queryRecoveryCandidatesAfter(
                scope, cutoff, cursor.position(), query.limit());
        appendRecoveryCandidates(candidates, tail, scope, cursor.cursorFingerprint(),
                cursor.cycleEpoch(), cutoff);
        int remaining = query.limit() - tail.size();
        if (remaining > 0) {
            if (cursor.cycleEpoch() == Long.MAX_VALUE) {
                throw new IllegalStateException("Durable worker scan cycle is exhausted");
            }
            appendRecoveryCandidates(candidates, queryRecoveryCandidatesAtOrBefore(
                            scope, cutoff, cursor.position(), remaining),
                    scope, cursor.cursorFingerprint(), cursor.cycleEpoch() + 1, cutoff);
        }
        return new RecoveryCandidatePage(candidates);
    }

    private void appendRecoveryCandidates(
            List<RecoveryCandidate> destination,
            List<DurableTestExecutionCheckpoint> checkpoints,
            WorkerAcquisitionScope scope,
            String expectedCursorFingerprint,
            long cycleEpoch,
            Instant observedAt) {
        Map<String, ActiveWorkerCandidateDeferral> activeDeferrals =
                activeWorkerCandidateDeferrals(scope, checkpoints, observedAt);
        Map<String, ActiveWorkerCandidateQuarantine> activeQuarantines =
                activeWorkerCandidateQuarantines(scope, checkpoints);
        for (DurableTestExecutionCheckpoint checkpoint : checkpoints) {
            DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
            WorkerScanProgress progress = new WorkerScanProgress(
                    scope, expectedCursorFingerprint, cycleEpoch,
                    lifecycle.leaseExpiresAt(), lifecycle.updatedAt(), checkpoint.runId());
            destination.add(new RecoveryCandidate(
                    checkpoint, progress,
                    Optional.ofNullable(activeDeferrals.get(checkpoint.runId())),
                    Optional.ofNullable(activeQuarantines.get(checkpoint.runId()))));
        }
    }

    private List<DurableTestExecutionCheckpoint> queryRecoveryCandidatesFromHead(
            WorkerAcquisitionScope scope, Instant cutoff, int limit) {
        return verifiedRecoveryCandidates(jdbc.query(selectColumns() + """
                        WHERE tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                          AND status IN ('ACTIVE', 'SUSPENDED', 'RESUMING')
                          AND lease_expires_at <= ?
                        ORDER BY lease_expires_at, updated_at, run_id
                        LIMIT ?
                        """, this::mapRow, scope.tenantId(), scope.environmentId(),
                scope.organizationId(), scope.projectId(), Timestamp.from(cutoff), limit));
    }

    private List<DurableTestExecutionCheckpoint> queryRecoveryCandidatesAfter(
            WorkerAcquisitionScope scope,
            Instant cutoff,
            WorkerScanPosition cursor,
            int limit) {
        Timestamp lease = Timestamp.from(cursor.leaseExpiresAt());
        Timestamp updated = Timestamp.from(cursor.updatedAt());
        return verifiedRecoveryCandidates(jdbc.query(selectColumns() + """
                        WHERE tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                          AND status IN ('ACTIVE', 'SUSPENDED', 'RESUMING')
                          AND lease_expires_at <= ?
                          AND (
                                lease_expires_at > ?
                                OR (lease_expires_at = ? AND updated_at > ?)
                                OR (lease_expires_at = ? AND updated_at = ? AND run_id > ?)
                              )
                        ORDER BY lease_expires_at, updated_at, run_id
                        LIMIT ?
                        """, this::mapRow, scope.tenantId(), scope.environmentId(),
                scope.organizationId(), scope.projectId(), Timestamp.from(cutoff), lease, lease,
                updated, lease, updated, cursor.runId(), limit));
    }

    private List<DurableTestExecutionCheckpoint> queryRecoveryCandidatesAtOrBefore(
            WorkerAcquisitionScope scope,
            Instant cutoff,
            WorkerScanPosition cursor,
            int limit) {
        Timestamp lease = Timestamp.from(cursor.leaseExpiresAt());
        Timestamp updated = Timestamp.from(cursor.updatedAt());
        return verifiedRecoveryCandidates(jdbc.query(selectColumns() + """
                        WHERE tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                          AND status IN ('ACTIVE', 'SUSPENDED', 'RESUMING')
                          AND lease_expires_at <= ?
                          AND (
                                lease_expires_at < ?
                                OR (lease_expires_at = ? AND updated_at < ?)
                                OR (lease_expires_at = ? AND updated_at = ? AND run_id <= ?)
                              )
                        ORDER BY lease_expires_at, updated_at, run_id
                        LIMIT ?
                        """, this::mapRow, scope.tenantId(), scope.environmentId(),
                scope.organizationId(), scope.projectId(), Timestamp.from(cutoff), lease, lease,
                updated, lease, updated, cursor.runId(), limit));
    }

    private List<DurableTestExecutionCheckpoint> verifiedRecoveryCandidates(
            List<StoredRow> rows) {
        return rows.stream().map(this::verifiedCheckpoint).toList();
    }

    private Map<String, ActiveWorkerCandidateDeferral> activeWorkerCandidateDeferrals(
            WorkerAcquisitionScope scope,
            List<DurableTestExecutionCheckpoint> checkpoints,
            Instant observedAt) {
        if (checkpoints.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(checkpoints.size(), "?"));
        Object[] arguments = new Object[checkpoints.size() + 1];
        arguments[0] = workerScanScopeKey(scope);
        Map<String, String> checkpointFingerprints = new HashMap<>();
        for (int index = 0; index < checkpoints.size(); index++) {
            DurableTestExecutionCheckpoint checkpoint = checkpoints.get(index);
            arguments[index + 1] = checkpoint.runId();
            if (checkpointFingerprints.putIfAbsent(
                    checkpoint.runId(), checkpoint.checkpointFingerprint()) != null) {
                throw new IllegalStateException("Worker candidate page contains a duplicate run");
            }
        }
        List<StoredWorkerCandidateDeferral> rows = jdbc.query(("""
                SELECT scope_key, tenant_id, environment_id, organization_id, project_id,
                       run_id, checkpoint_fingerprint, reason, consecutive_failures,
                       first_observed_at, last_observed_at, retry_after, record_fingerprint
                FROM rg_test_durable_worker_candidate_deferrals
                WHERE scope_key = ? AND run_id IN (%s)
                """).formatted(placeholders), this::mapWorkerCandidateDeferral, arguments);
        Map<String, ActiveWorkerCandidateDeferral> active = new HashMap<>();
        Set<String> storedRuns = new HashSet<>();
        for (StoredWorkerCandidateDeferral stored : rows) {
            requireValidWorkerCandidateDeferral(stored);
            if (!scope.equals(stored.scope())) {
                throw new IllegalStateException("Stored worker candidate deferral scope is corrupt");
            }
            if (!storedRuns.add(stored.runId())) {
                throw new IllegalStateException(
                        "Stored worker candidate deferral contains a duplicate run");
            }
            if (stored.checkpointFingerprint().equals(
                    checkpointFingerprints.get(stored.runId()))
                    && stored.retryAfter().isAfter(observedAt)) {
                active.put(stored.runId(), stored.activeDeferral());
            }
        }
        return Map.copyOf(active);
    }

    private Map<String, ActiveWorkerCandidateQuarantine> activeWorkerCandidateQuarantines(
            WorkerAcquisitionScope scope,
            List<DurableTestExecutionCheckpoint> checkpoints) {
        if (checkpoints.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(checkpoints.size(), "?"));
        Object[] arguments = new Object[checkpoints.size() + 1];
        arguments[0] = workerScanScopeKey(scope);
        Map<String, String> checkpointFingerprints = new HashMap<>();
        for (int index = 0; index < checkpoints.size(); index++) {
            DurableTestExecutionCheckpoint checkpoint = checkpoints.get(index);
            arguments[index + 1] = checkpoint.runId();
            if (checkpointFingerprints.putIfAbsent(
                    checkpoint.runId(), checkpoint.checkpointFingerprint()) != null) {
                throw new IllegalStateException("Worker candidate page contains a duplicate run");
            }
        }
        List<StoredWorkerCandidateQuarantine> rows = jdbc.query(("""
                SELECT scope_key, tenant_id, environment_id, organization_id, project_id,
                       run_id, checkpoint_fingerprint, reason, consecutive_failures,
                       quarantine_threshold, first_observed_at, quarantined_at,
                       record_fingerprint
                FROM rg_test_durable_worker_candidate_quarantines
                WHERE scope_key = ? AND run_id IN (%s)
                """).formatted(placeholders), this::mapWorkerCandidateQuarantine, arguments);
        Map<String, ActiveWorkerCandidateQuarantine> active = new HashMap<>();
        Set<String> storedRuns = new HashSet<>();
        for (StoredWorkerCandidateQuarantine stored : rows) {
            requireValidWorkerCandidateQuarantine(stored);
            if (!scope.equals(stored.scope())) {
                throw new IllegalStateException(
                        "Stored worker candidate quarantine scope is corrupt");
            }
            if (!storedRuns.add(stored.runId())) {
                throw new IllegalStateException(
                        "Stored worker candidate quarantine contains a duplicate run");
            }
            if (stored.checkpointFingerprint().equals(
                    checkpointFingerprints.get(stored.runId()))) {
                active.put(stored.runId(), stored.activeQuarantine());
            }
        }
        return Map.copyOf(active);
    }

    private Optional<StoredWorkerCandidateDeferral> findWorkerCandidateDeferral(
            WorkerAcquisitionScope scope,
            String runId) {
        List<StoredWorkerCandidateDeferral> rows = jdbc.query("""
                        SELECT scope_key, tenant_id, environment_id, organization_id, project_id,
                               run_id, checkpoint_fingerprint, reason, consecutive_failures,
                               first_observed_at, last_observed_at, retry_after, record_fingerprint
                        FROM rg_test_durable_worker_candidate_deferrals
                        WHERE scope_key = ? AND run_id = ?
                        """, this::mapWorkerCandidateDeferral,
                workerScanScopeKey(scope), runId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredWorkerCandidateDeferral stored = rows.getFirst();
        requireValidWorkerCandidateDeferral(stored);
        if (!scope.equals(stored.scope())) {
            throw new IllegalStateException("Stored worker candidate deferral scope is corrupt");
        }
        return Optional.of(stored);
    }

    private Optional<StoredWorkerCandidateQuarantine> findWorkerCandidateQuarantine(
            WorkerAcquisitionScope scope,
            String runId) {
        List<StoredWorkerCandidateQuarantine> rows = jdbc.query("""
                        SELECT scope_key, tenant_id, environment_id, organization_id, project_id,
                               run_id, checkpoint_fingerprint, reason, consecutive_failures,
                               quarantine_threshold, first_observed_at, quarantined_at,
                               record_fingerprint
                        FROM rg_test_durable_worker_candidate_quarantines
                        WHERE scope_key = ? AND run_id = ?
                        """, this::mapWorkerCandidateQuarantine,
                workerScanScopeKey(scope), runId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredWorkerCandidateQuarantine stored = rows.getFirst();
        requireValidWorkerCandidateQuarantine(stored);
        if (!scope.equals(stored.scope())) {
            throw new IllegalStateException(
                    "Stored worker candidate quarantine scope is corrupt");
        }
        return Optional.of(stored);
    }

    private StoredWorkerCandidateDeferral mapWorkerCandidateDeferral(
            ResultSet rs,
            int rowNumber) throws SQLException {
        return new StoredWorkerCandidateDeferral(
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("environment_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("reason"),
                rs.getLong("consecutive_failures"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("last_observed_at").toInstant(),
                rs.getTimestamp("retry_after").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private StoredWorkerCandidateQuarantine mapWorkerCandidateQuarantine(
            ResultSet rs,
            int rowNumber) throws SQLException {
        return new StoredWorkerCandidateQuarantine(
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("environment_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("reason"),
                rs.getLong("consecutive_failures"), rs.getInt("quarantine_threshold"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("quarantined_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private void requireValidWorkerCandidateDeferral(StoredWorkerCandidateDeferral stored) {
        WorkerAcquisitionScope scope;
        WorkerCandidateDeferralReason reason;
        ActiveWorkerCandidateDeferral active;
        try {
            scope = stored.scope();
            reason = WorkerCandidateDeferralReason.valueOf(stored.reason());
            active = new ActiveWorkerCandidateDeferral(
                    reason, stored.consecutiveFailures(), stored.firstObservedAt(),
                    stored.lastObservedAt(), stored.retryAfter());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker candidate deferral is corrupt", invalid);
        }
        String expected = workerCandidateDeferralFingerprint(
                scope, stored.runId(), stored.checkpointFingerprint(), active);
        if (!workerScanScopeKey(scope).equals(stored.scopeKey())
                || !expected.equals(stored.recordFingerprint())) {
            throw new IllegalStateException("Stored worker candidate deferral is corrupt");
        }
    }

    private void requireValidWorkerCandidateQuarantine(
            StoredWorkerCandidateQuarantine stored) {
        WorkerAcquisitionScope scope;
        ActiveWorkerCandidateQuarantine active;
        try {
            scope = stored.scope();
            active = stored.activeQuarantine();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Stored worker candidate quarantine is corrupt", invalid);
        }
        String expected = workerCandidateQuarantineFingerprint(
                scope, stored.runId(), stored.checkpointFingerprint(), active);
        if (!workerScanScopeKey(scope).equals(stored.scopeKey())
                || !expected.equals(stored.recordFingerprint())) {
            throw new IllegalStateException("Stored worker candidate quarantine is corrupt");
        }
    }

    private void requireValidWorkerDeferrals(
            WorkerAcquisitionCommand command,
            Optional<WorkerAcquisitionSelection> selection,
            Optional<WorkerScanProgress> scanProgress,
            List<WorkerCandidateDeferral> deferrals) {
        if (!deferrals.isEmpty() && scanProgress.isEmpty()) {
            throw new IllegalArgumentException(
                    "Worker candidate deferrals require committed scan progress");
        }
        Map<String, WorkerCandidateDeferral> unique = new HashMap<>();
        for (WorkerCandidateDeferral deferral : deferrals) {
            if (deferral == null) {
                throw new IllegalArgumentException("Worker candidate deferrals contain null");
            }
            WorkerScanProgress observed = deferral.observedProgress();
            WorkerScanProgress terminal = scanProgress.orElseThrow();
            if (!command.scope().equals(deferral.scope())
                    || !observed.expectedCursorFingerprint().equals(
                    terminal.expectedCursorFingerprint())
                    || compareWorkerScanProgress(observed, terminal) > 0) {
                throw new IllegalArgumentException(
                        "Worker candidate deferral is outside committed scan progress");
            }
            if (selection.isPresent()
                    && selection.orElseThrow().claim().runId().equals(deferral.runId())) {
                throw new IllegalArgumentException(
                        "Selected worker candidate cannot also be deferred");
            }
            if (unique.putIfAbsent(deferral.runId(), deferral) != null) {
                throw new IllegalArgumentException(
                        "Worker candidate deferrals contain a duplicate run");
            }
        }
    }

    private int compareWorkerScanProgress(
            WorkerScanProgress left,
            WorkerScanProgress right) {
        int cycle = Long.compare(left.nextCycleEpoch(), right.nextCycleEpoch());
        if (cycle != 0) {
            return cycle;
        }
        return new WorkerScanPosition(
                left.nextLeaseExpiresAt(), left.nextUpdatedAt(), left.nextRunId())
                .compareTo(new WorkerScanPosition(
                        right.nextLeaseExpiresAt(), right.nextUpdatedAt(), right.nextRunId()));
    }

    private void persistWorkerCandidateDeferrals(
            WorkerAcquisitionScope scope,
            List<WorkerCandidateDeferral> deferrals,
            Instant observedAt) {
        for (WorkerCandidateDeferral deferral : deferrals) {
            Optional<DurableTestExecutionCheckpoint> current =
                    findWorkerSchedulingCheckpointForUpdate(
                            scope.tenantId(), scope.environmentId(), deferral.runId());
            if (current.isEmpty()
                    || !scope.contains(current.orElseThrow())
                    || !deferral.checkpointFingerprint().equals(
                    current.orElseThrow().checkpointFingerprint())) {
                continue;
            }
            Optional<StoredWorkerCandidateQuarantine> quarantined =
                    findWorkerCandidateQuarantine(scope, deferral.runId());
            if (quarantined.isPresent()) {
                if (!quarantined.orElseThrow().checkpointFingerprint().equals(
                        deferral.checkpointFingerprint())) {
                    throw new IllegalStateException(
                            "Stored worker candidate quarantine conflicts with live checkpoint");
                }
                continue;
            }
            Optional<StoredWorkerCandidateDeferral> prior = findWorkerCandidateDeferral(
                    scope, deferral.runId());
            if (prior.isPresent()
                    && prior.orElseThrow().checkpointFingerprint().equals(
                    deferral.checkpointFingerprint())
                    && prior.orElseThrow().retryAfter().isAfter(observedAt)) {
                continue;
            }
            boolean consecutive = prior.isPresent()
                    && prior.orElseThrow().checkpointFingerprint().equals(
                    deferral.checkpointFingerprint())
                    && prior.orElseThrow().reason().equals(deferral.reason().name());
            long failures = consecutive
                    ? saturatingIncrement(prior.orElseThrow().consecutiveFailures()) : 1;
            Instant firstObservedAt = consecutive
                    ? prior.orElseThrow().firstObservedAt() : observedAt;
            if (failures >= deferral.quarantineThreshold()) {
                ActiveWorkerCandidateQuarantine quarantine =
                        new ActiveWorkerCandidateQuarantine(
                                deferral.reason(), failures, deferral.quarantineThreshold(),
                                firstObservedAt, observedAt);
                insertWorkerCandidateQuarantine(
                        scope, deferral.runId(), deferral.checkpointFingerprint(), quarantine);
                clearWorkerCandidateDeferral(
                        scope, deferral.runId(), deferral.checkpointFingerprint());
                continue;
            }
            Duration delay = exponentialBackoff(
                    deferral.initialBackoff(), deferral.maximumBackoff(), failures);
            Instant retryAfter = observedAt.plus(delay);
            ActiveWorkerCandidateDeferral active = new ActiveWorkerCandidateDeferral(
                    deferral.reason(), failures, firstObservedAt, observedAt, retryAfter);
            String fingerprint = workerCandidateDeferralFingerprint(
                    scope, deferral.runId(), deferral.checkpointFingerprint(), active);
            jdbc.update("""
                    MERGE INTO rg_test_durable_worker_candidate_deferrals (
                        scope_key, tenant_id, environment_id, organization_id, project_id,
                        run_id, checkpoint_fingerprint, reason, consecutive_failures,
                        first_observed_at, last_observed_at, retry_after, record_fingerprint
                    ) KEY (scope_key, run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, workerScanScopeKey(scope), scope.tenantId(), scope.environmentId(),
                    scope.organizationId(), scope.projectId(), deferral.runId(),
                    deferral.checkpointFingerprint(), deferral.reason().name(), failures,
                    Timestamp.from(firstObservedAt), Timestamp.from(observedAt),
                    Timestamp.from(retryAfter), fingerprint);
        }
    }

    private void insertWorkerCandidateQuarantine(
            WorkerAcquisitionScope scope,
            String runId,
            String checkpointFingerprint,
            ActiveWorkerCandidateQuarantine quarantine) {
        String fingerprint = workerCandidateQuarantineFingerprint(
                scope, runId, checkpointFingerprint, quarantine);
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_candidate_quarantines (
                    scope_key, tenant_id, environment_id, organization_id, project_id,
                    run_id, checkpoint_fingerprint, reason, consecutive_failures,
                    quarantine_threshold, first_observed_at, quarantined_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, workerScanScopeKey(scope), scope.tenantId(), scope.environmentId(),
                scope.organizationId(), scope.projectId(), runId, checkpointFingerprint,
                quarantine.reason().name(), quarantine.consecutiveFailures(),
                quarantine.quarantineThreshold(), Timestamp.from(quarantine.firstObservedAt()),
                Timestamp.from(quarantine.quarantinedAt()), fingerprint);
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Durable worker candidate quarantine was not inserted exactly once");
        }
    }

    private void clearWorkerCandidateDeferral(
            WorkerAcquisitionScope scope,
            String runId,
            String checkpointFingerprint) {
        Optional<StoredWorkerCandidateDeferral> stored = findWorkerCandidateDeferral(scope, runId);
        if (stored.isEmpty()
                || !stored.orElseThrow().checkpointFingerprint().equals(checkpointFingerprint)) {
            return;
        }
        int deleted = jdbc.update("""
                        DELETE FROM rg_test_durable_worker_candidate_deferrals
                        WHERE scope_key = ? AND run_id = ?
                          AND checkpoint_fingerprint = ? AND record_fingerprint = ?
                        """, stored.orElseThrow().scopeKey(), runId, checkpointFingerprint,
                stored.orElseThrow().recordFingerprint());
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Durable worker candidate deferral changed while clearing");
        }
    }

    private void clearWorkerCandidateQuarantine(
            WorkerAcquisitionScope scope,
            String runId,
            String checkpointFingerprint) {
        Optional<StoredWorkerCandidateQuarantine> stored = findWorkerCandidateQuarantine(
                scope, runId);
        if (stored.isEmpty()
                || !stored.orElseThrow().checkpointFingerprint().equals(checkpointFingerprint)) {
            return;
        }
        int deleted = jdbc.update("""
                        DELETE FROM rg_test_durable_worker_candidate_quarantines
                        WHERE scope_key = ? AND run_id = ?
                          AND checkpoint_fingerprint = ? AND record_fingerprint = ?
                        """, stored.orElseThrow().scopeKey(), runId, checkpointFingerprint,
                stored.orElseThrow().recordFingerprint());
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Durable worker candidate quarantine changed while clearing");
        }
    }

    private void clearWorkerCandidateSchedulingState(
            WorkerAcquisitionScope scope,
            String runId,
            String checkpointFingerprint) {
        clearWorkerCandidateDeferral(scope, runId, checkpointFingerprint);
        clearWorkerCandidateQuarantine(scope, runId, checkpointFingerprint);
    }

    private void requireWorkerCandidateNotQuarantined(
            WorkerAcquisitionScope scope,
            LeaseClaim claim) {
        Optional<StoredWorkerCandidateQuarantine> stored = findWorkerCandidateQuarantine(
                scope, claim.runId());
        if (stored.isPresent()
                && stored.orElseThrow().checkpointFingerprint().equals(
                claim.expectedCheckpointFingerprint())) {
            throw conflict(NOT_RESUMABLE,
                    "Selected durable checkpoint is quarantined from worker acquisition");
        }
    }

    private Duration exponentialBackoff(
            Duration initial,
            Duration maximum,
            long consecutiveFailures) {
        Duration delay = initial;
        long remainingDoublings = Math.max(0, consecutiveFailures - 1);
        while (remainingDoublings > 0 && delay.compareTo(maximum) < 0) {
            delay = delay.compareTo(maximum.dividedBy(2)) > 0
                    ? maximum : delay.multipliedBy(2);
            remainingDoublings--;
        }
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }

    private long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    private String workerCandidateDeferralFingerprint(
            WorkerAcquisitionScope scope,
            String runId,
            String checkpointFingerprint,
            ActiveWorkerCandidateDeferral active) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerCandidateDeferral.v1"),
                Map.entry("scope", scope),
                Map.entry("runId", runId),
                Map.entry("checkpointFingerprint", checkpointFingerprint),
                Map.entry("reason", active.reason().name()),
                Map.entry("consecutiveFailures", active.consecutiveFailures()),
                Map.entry("firstObservedAt", active.firstObservedAt()),
                Map.entry("lastObservedAt", active.lastObservedAt()),
                Map.entry("retryAfter", active.retryAfter())));
    }

    private String workerCandidateQuarantineFingerprint(
            WorkerAcquisitionScope scope,
            String runId,
            String checkpointFingerprint,
            ActiveWorkerCandidateQuarantine active) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerCandidateQuarantine.v1"),
                Map.entry("scope", scope),
                Map.entry("runId", runId),
                Map.entry("checkpointFingerprint", checkpointFingerprint),
                Map.entry("reason", active.reason().name()),
                Map.entry("consecutiveFailures", active.consecutiveFailures()),
                Map.entry("quarantineThreshold", active.quarantineThreshold()),
                Map.entry("firstObservedAt", active.firstObservedAt()),
                Map.entry("quarantinedAt", active.quarantinedAt())));
    }

    private Optional<WorkerScanCursorSnapshot> findWorkerScanCursor(
            WorkerAcquisitionScope scope) {
        List<StoredWorkerScanCursor> rows = jdbc.query("""
                        SELECT scope_key, tenant_id, environment_id, organization_id, project_id,
                               cycle_epoch, cursor_lease_expires_at, cursor_updated_at,
                               cursor_run_id, advanced_at, cursor_fingerprint
                        FROM rg_test_durable_worker_scan_cursors
                        WHERE scope_key = ?
                        """, this::mapWorkerScanCursor, workerScanScopeKey(scope));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredWorkerScanCursor stored = rows.getFirst();
        requireValidWorkerScanCursor(stored);
        if (!scope.equals(stored.scope())) {
            throw new IllegalStateException("Stored worker scan cursor scope is corrupt");
        }
        return Optional.of(stored.snapshot());
    }

    private WorkerScanCursorSnapshot emptyWorkerScanCursor(WorkerAcquisitionScope scope) {
        return new WorkerScanCursorSnapshot(
                0, null, emptyWorkerScanCursorFingerprint(scope));
    }

    private String emptyWorkerScanCursorFingerprint(WorkerAcquisitionScope scope) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.durableWorkerScanCursorEmpty.v1",
                "scope", scope));
    }

    private boolean advanceWorkerScanCursor(
            WorkerAcquisitionScope scope,
            WorkerScanProgress progress,
            Instant advancedAt) {
        lockWorkerScanCursor(scope);
        Optional<WorkerScanCursorSnapshot> currentStored = findWorkerScanCursor(scope);
        WorkerScanCursorSnapshot current = currentStored.orElseGet(
                () -> emptyWorkerScanCursor(scope));
        if (!current.cursorFingerprint().equals(progress.expectedCursorFingerprint())) {
            return false;
        }
        WorkerScanPosition nextPosition = new WorkerScanPosition(
                progress.nextLeaseExpiresAt(), progress.nextUpdatedAt(), progress.nextRunId());
        requireValidWorkerScanAdvance(current, progress.nextCycleEpoch(), nextPosition);
        String nextFingerprint = workerScanCursorFingerprint(
                scope, progress.nextCycleEpoch(), nextPosition, advancedAt);
        StoredWorkerScanCursor next = new StoredWorkerScanCursor(
                workerScanScopeKey(scope), scope.tenantId(), scope.environmentId(),
                scope.organizationId(),
                scope.projectId(), progress.nextCycleEpoch(), nextPosition.leaseExpiresAt(),
                nextPosition.updatedAt(), nextPosition.runId(), advancedAt, nextFingerprint);
        if (currentStored.isEmpty()) {
            insertWorkerScanCursor(next);
        } else {
            int updated = jdbc.update("""
                            UPDATE rg_test_durable_worker_scan_cursors
                            SET cycle_epoch = ?, cursor_lease_expires_at = ?,
                                cursor_updated_at = ?, cursor_run_id = ?, advanced_at = ?,
                                cursor_fingerprint = ?
                            WHERE scope_key = ? AND cursor_fingerprint = ?
                            """, next.cycleEpoch(), Timestamp.from(next.cursorLeaseExpiresAt()),
                    Timestamp.from(next.cursorUpdatedAt()), next.cursorRunId(),
                    Timestamp.from(next.advancedAt()), next.cursorFingerprint(), next.scopeKey(),
                    current.cursorFingerprint());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Durable worker scan cursor changed while locked");
            }
        }
        return true;
    }

    private void requireValidWorkerScanAdvance(
            WorkerScanCursorSnapshot current,
            long nextCycleEpoch,
            WorkerScanPosition nextPosition) {
        if (current.position() == null) {
            if (nextCycleEpoch != 0) {
                throw new IllegalArgumentException(
                        "Initial worker scan progress must start in cycle zero");
            }
            return;
        }
        int positionOrder = nextPosition.compareTo(current.position());
        if (nextCycleEpoch == current.cycleEpoch()) {
            if (positionOrder <= 0) {
                throw new IllegalArgumentException(
                        "Worker scan progress must advance within its current cycle");
            }
            return;
        }
        if (current.cycleEpoch() == Long.MAX_VALUE
                || nextCycleEpoch != current.cycleEpoch() + 1
                || positionOrder > 0) {
            throw new IllegalArgumentException(
                    "Worker scan progress may wrap only once to an earlier keyset position");
        }
    }

    private void lockWorkerScanCursor(WorkerAcquisitionScope scope) {
        jdbc.update("""
                MERGE INTO rg_test_durable_worker_scan_cursor_locks (
                            scope_key, tenant_id, environment_id, organization_id, project_id
                        ) KEY (scope_key)
                        VALUES (?, ?, ?, ?, ?)
                        """, workerScanScopeKey(scope), scope.tenantId(), scope.environmentId(),
                scope.organizationId(), scope.projectId());
        jdbc.queryForObject("""
                        SELECT scope_key FROM rg_test_durable_worker_scan_cursor_locks
                        WHERE scope_key = ?
                        FOR UPDATE
                        """, String.class, workerScanScopeKey(scope));
    }

    private void insertWorkerScanCursor(StoredWorkerScanCursor cursor) {
        jdbc.update("""
                        INSERT INTO rg_test_durable_worker_scan_cursors (
                            scope_key, tenant_id, environment_id, organization_id, project_id,
                            cycle_epoch, cursor_lease_expires_at, cursor_updated_at,
                            cursor_run_id, advanced_at, cursor_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, cursor.scopeKey(), cursor.tenantId(), cursor.environmentId(),
                cursor.organizationId(),
                cursor.projectId(), cursor.cycleEpoch(),
                Timestamp.from(cursor.cursorLeaseExpiresAt()),
                Timestamp.from(cursor.cursorUpdatedAt()), cursor.cursorRunId(),
                Timestamp.from(cursor.advancedAt()), cursor.cursorFingerprint());
    }

    private StoredWorkerScanCursor mapWorkerScanCursor(ResultSet rs, int rowNumber)
            throws SQLException {
        return new StoredWorkerScanCursor(
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("environment_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getLong("cycle_epoch"),
                rs.getTimestamp("cursor_lease_expires_at").toInstant(),
                rs.getTimestamp("cursor_updated_at").toInstant(),
                rs.getString("cursor_run_id"), rs.getTimestamp("advanced_at").toInstant(),
                rs.getString("cursor_fingerprint"));
    }

    private void requireValidWorkerScanCursor(StoredWorkerScanCursor stored) {
        WorkerAcquisitionScope scope;
        WorkerScanPosition position;
        try {
            scope = stored.scope();
            position = stored.position();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker scan cursor is corrupt", invalid);
        }
        String expected = workerScanCursorFingerprint(
                scope, stored.cycleEpoch(), position, stored.advancedAt());
        if (stored.cycleEpoch() < 0
                || !workerScanScopeKey(scope).equals(stored.scopeKey())
                || !expected.equals(stored.cursorFingerprint())) {
            throw new IllegalStateException("Stored worker scan cursor is corrupt");
        }
    }

    private String workerScanScopeKey(WorkerAcquisitionScope scope) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.durableWorkerScanScope.v1",
                "scope", scope));
    }

    private String workerScanCursorFingerprint(
            WorkerAcquisitionScope scope,
            long cycleEpoch,
            WorkerScanPosition position,
            Instant advancedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerScanCursor.v1"),
                Map.entry("scope", scope),
                Map.entry("cycleEpoch", cycleEpoch),
                Map.entry("leaseExpiresAt", position.leaseExpiresAt()),
                Map.entry("updatedAt", position.updatedAt()),
                Map.entry("runId", position.runId()),
                Map.entry("advancedAt", advancedAt)));
    }

    private Optional<WorkerAcquisitionResult> replayedWorkerAcquisition(
            WorkerAcquisitionCommand command) {
        List<StoredWorkerAcquisition> rows = findWorkerAcquisitions(
                command.scope(), command.clientRequestId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredWorkerAcquisition stored = rows.getFirst();
        requireValidWorkerAcquisition(stored);
        if (!stored.requestFingerprint().equals(command.requestFingerprint())) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different worker acquisition intent");
        }
        return Optional.of(workerAcquisitionResult(stored, true));
    }

    private List<StoredWorkerAcquisition> findWorkerAcquisitions(
            WorkerAcquisitionScope scope, String clientRequestId) {
        return jdbc.query("""
                        SELECT tenant_id, environment_id, client_request_id,
                               request_fingerprint, organization_id, project_id, outcome,
                               observed_at, run_id, result_checkpoint_fingerprint,
                               result_dispatch_fingerprint, result_checkpoint_json,
                               result_dispatch_json, record_fingerprint
                        FROM rg_test_durable_worker_acquisitions
                        WHERE tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                          AND client_request_id = ?
                        """, this::mapWorkerAcquisition, scope.tenantId(),
                scope.environmentId(), scope.organizationId(), scope.projectId(),
                normalized(clientRequestId));
    }

    private StoredWorkerAcquisition newWorkerAcquisition(
            WorkerAcquisitionCommand command,
            WorkerAcquisitionOutcome outcome,
            Instant observedAt,
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryDispatch dispatch) {
        WorkerAcquisitionScope scope = command.scope();
        String runId = checkpoint == null ? "" : checkpoint.runId();
        String checkpointFingerprint = checkpoint == null
                ? "" : checkpoint.checkpointFingerprint();
        String dispatchFingerprint = dispatch == null ? "" : dispatch.dispatchFingerprint();
        String recordFingerprint = workerAcquisitionRecordFingerprint(
                scope, command.clientRequestId(), command.requestFingerprint(), outcome,
                observedAt, runId, checkpointFingerprint, dispatchFingerprint);
        return new StoredWorkerAcquisition(
                scope.tenantId(), scope.environmentId(), command.clientRequestId(),
                command.requestFingerprint(), scope.organizationId(), scope.projectId(), outcome,
                observedAt, runId, checkpointFingerprint, dispatchFingerprint,
                checkpoint == null ? null : write(checkpoint),
                dispatch == null ? null : writeDispatch(dispatch), recordFingerprint);
    }

    private void insertWorkerAcquisition(StoredWorkerAcquisition stored) {
        jdbc.update("""
                INSERT INTO rg_test_durable_worker_acquisitions (
                    tenant_id, environment_id, client_request_id, request_fingerprint,
                    organization_id, project_id, outcome, observed_at, run_id,
                    result_checkpoint_fingerprint, result_dispatch_fingerprint,
                    result_checkpoint_json, result_dispatch_json, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stored.tenantId(), stored.environmentId(), stored.clientRequestId(),
                stored.requestFingerprint(), stored.organizationId(), stored.projectId(),
                stored.outcome().name(), Timestamp.from(stored.observedAt()), stored.runId(),
                stored.resultCheckpointFingerprint(), stored.resultDispatchFingerprint(),
                stored.resultCheckpointJson(), stored.resultDispatchJson(),
                stored.recordFingerprint());
    }

    private StoredWorkerAcquisition mapWorkerAcquisition(ResultSet rs, int rowNumber)
            throws SQLException {
        try {
            return new StoredWorkerAcquisition(
                    rs.getString("tenant_id"), rs.getString("environment_id"),
                    rs.getString("client_request_id"), rs.getString("request_fingerprint"),
                    rs.getString("organization_id"), rs.getString("project_id"),
                    WorkerAcquisitionOutcome.valueOf(rs.getString("outcome")),
                    rs.getTimestamp("observed_at").toInstant(), rs.getString("run_id"),
                    rs.getString("result_checkpoint_fingerprint"),
                    rs.getString("result_dispatch_fingerprint"),
                    rs.getString("result_checkpoint_json"),
                    rs.getString("result_dispatch_json"), rs.getString("record_fingerprint"));
        } catch (IllegalArgumentException corrupt) {
            throw new SQLException("Stored worker acquisition outcome is corrupt", corrupt);
        }
    }

    private void requireValidWorkerAcquisition(StoredWorkerAcquisition stored) {
        WorkerAcquisitionScope scope = stored.scope();
        String expected = workerAcquisitionRecordFingerprint(
                scope, stored.clientRequestId(), stored.requestFingerprint(), stored.outcome(),
                stored.observedAt(), stored.runId(), stored.resultCheckpointFingerprint(),
                stored.resultDispatchFingerprint());
        if (!expected.equals(stored.recordFingerprint())) {
            throw new IllegalStateException("Stored worker acquisition record is corrupt");
        }
        boolean acquired = stored.outcome() == WorkerAcquisitionOutcome.ACQUIRED;
        boolean completeResult = !stored.runId().isBlank()
                && !stored.resultCheckpointFingerprint().isBlank()
                && !stored.resultDispatchFingerprint().isBlank()
                && stored.resultCheckpointJson() != null
                && stored.resultDispatchJson() != null;
        boolean emptyResult = stored.runId().isBlank()
                && stored.resultCheckpointFingerprint().isBlank()
                && stored.resultDispatchFingerprint().isBlank()
                && stored.resultCheckpointJson() == null
                && stored.resultDispatchJson() == null;
        if ((acquired && !completeResult) || (!acquired && !emptyResult)) {
            throw new IllegalStateException("Stored worker acquisition result shape is corrupt");
        }
    }

    private WorkerAcquisitionResult workerAcquisitionResult(
            StoredWorkerAcquisition stored, boolean replay) {
        if (stored.outcome() == WorkerAcquisitionOutcome.NO_WORK) {
            return new WorkerAcquisitionResult(
                    stored.outcome(), stored.observedAt(), null, null, replay);
        }
        try {
            DurableTestExecutionCheckpoint checkpoint = objectMapper.readValue(
                    stored.resultCheckpointJson(), DurableTestExecutionCheckpoint.class);
            DurableTestRecoveryDispatch dispatch = objectMapper.readValue(
                    stored.resultDispatchJson(), DurableTestRecoveryDispatch.class);
            integrity.requireValid(checkpoint);
            dispatch.requireValid(objectMapper, checkpoint);
            if (!stored.scope().contains(checkpoint)
                    || !stored.runId().equals(checkpoint.runId())
                    || !stored.resultCheckpointFingerprint().equals(
                    checkpoint.checkpointFingerprint())
                    || !stored.resultDispatchFingerprint().equals(
                    dispatch.dispatchFingerprint())) {
                throw new IllegalArgumentException(
                        "Worker acquisition result does not agree with its command record");
            }
            return new WorkerAcquisitionResult(
                    stored.outcome(), stored.observedAt(), checkpoint, dispatch, replay);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored worker acquisition result is corrupt", corrupt);
        }
    }

    private String workerAcquisitionRecordFingerprint(
            WorkerAcquisitionScope scope,
            String clientRequestId,
            String requestFingerprint,
            WorkerAcquisitionOutcome outcome,
            Instant observedAt,
            String runId,
            String checkpointFingerprint,
            String dispatchFingerprint) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerAcquisitionRecord.v1"),
                Map.entry("scope", scope),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("outcome", outcome.name()),
                Map.entry("observedAt", observedAt),
                Map.entry("runId", runId),
                Map.entry("resultCheckpointFingerprint", checkpointFingerprint),
                Map.entry("resultDispatchFingerprint", dispatchFingerprint)));
    }

    private DurableTestExecutionCheckpoint claimExpiredLeaseAt(LeaseClaim claim, Instant claimedAt) {
        DurableTestExecutionCheckpoint current = findInternal(
                claim.tenantId(), claim.environmentId(), claim.runId()).orElseThrow(() -> conflict(
                STALE_FENCE, "Durable checkpoint no longer exists in the expected scope"));
        requireClaimable(current, claim, claimedAt);
        var lifecycle = current.lifecycle();
        DurableTestExecutionCheckpoint claimed = integrity.seal(
                new DurableTestExecutionCheckpoint(
                        current.schemaVersion(), current.scope(), current.runId(),
                        current.engineExecutionId(), current.dependencies(),
                        current.fixtureConsumptionState(), current.executionServiceState(),
                        current.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.RESUMING,
                        claim.claimantOwnerId(), lifecycle.leaseEpoch() + 1,
                        lifecycle.revision() + 1, lifecycle.createdAt(), claimedAt,
                        claimedAt.plus(claim.leaseDuration())), ""));
        if (claimUpdate(claimed, claim, claimedAt) != 1) {
            throw conflict(STALE_FENCE, "Durable checkpoint lease changed concurrently");
        }
        clearWorkerCandidateSchedulingState(
                new WorkerAcquisitionScope(
                        current.scope().tenantId(), current.scope().organizationId(),
                        current.scope().projectId(), current.scope().environmentId()),
                current.runId(), current.checkpointFingerprint());
        return claimed;
    }

    private Optional<LeaseClaimResult> replayedCommand(ResumeLeaseCommand command) {
        LeaseClaim claim = command.claim();
        List<StoredResumeCommand> rows = findResumeCommands(
                claim.tenantId(), claim.environmentId(), command.clientRequestId());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredResumeCommand stored = rows.getFirst();
        requireValidResumeCommandRecord(stored);
        if (!stored.matches(command)) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies different durable resume intent");
        }
        return Optional.of(verifiedCommandResult(stored, true));
    }

    private List<StoredResumeCommand> findResumeCommands(
            String tenantId, String environmentId, String clientRequestId) {
        return jdbc.query(resumeCommandSelect() + """
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                        """, this::mapResumeCommand, tenantId, environmentId, clientRequestId);
    }

    private static String resumeCommandSelect() {
        return """
                SELECT tenant_id, environment_id, client_request_id, request_fingerprint,
                       run_id, expected_owner_id, expected_lease_epoch, expected_revision,
                       expected_checkpoint_fingerprint, claimant_owner_id,
                       lease_duration_seconds, authorization_fingerprint,
                       result_checkpoint_fingerprint, result_dispatch_fingerprint,
                       record_fingerprint, result_checkpoint_json, result_dispatch_json, created_at
                FROM rg_test_durable_resume_commands
                """;
    }

    private void insertResumeCommand(ResumeLeaseCommand command,
                                     DurableTestExecutionCheckpoint result,
                                     DurableTestRecoveryDispatch dispatch,
                                     Instant createdAt) {
        LeaseClaim claim = command.claim();
        Fence fence = claim.expectedFence();
        String recordFingerprint = resumeCommandRecordFingerprint(
                command, result.checkpointFingerprint(), dispatch.dispatchFingerprint(), createdAt);
        jdbc.update("""
                INSERT INTO rg_test_durable_resume_commands (
                    tenant_id, environment_id, client_request_id, request_fingerprint, run_id,
                    expected_owner_id, expected_lease_epoch, expected_revision,
                    expected_checkpoint_fingerprint, claimant_owner_id, lease_duration_seconds,
                    authorization_fingerprint, result_checkpoint_fingerprint,
                    result_dispatch_fingerprint, record_fingerprint, result_checkpoint_json,
                    result_dispatch_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, claim.tenantId(), claim.environmentId(), command.clientRequestId(),
                command.requestFingerprint(), claim.runId(), fence.ownerId(), fence.leaseEpoch(),
                fence.revision(), claim.expectedCheckpointFingerprint(), claim.claimantOwnerId(),
                claim.leaseDuration().toSeconds(), command.authorization().authorizationFingerprint(),
                result.checkpointFingerprint(), dispatch.dispatchFingerprint(), recordFingerprint,
                write(result), writeDispatch(dispatch), Timestamp.from(createdAt));
    }

    private LeaseClaimResult verifiedCommandResult(
            StoredResumeCommand stored, boolean idempotentReplay) {
        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = objectMapper.readValue(
                    stored.resultCheckpointJson(), DurableTestExecutionCheckpoint.class);
            integrity.requireValid(checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored durable resume command result is corrupt", corrupt);
        }
        DurableTestRecoveryDispatch dispatch;
        try {
            dispatch = objectMapper.readValue(
                    stored.resultDispatchJson(), DurableTestRecoveryDispatch.class);
            dispatch.requireValid(objectMapper, checkpoint);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored durable recovery dispatch result is corrupt", corrupt);
        }
        if (!stored.agreesWith(checkpoint, dispatch)) {
            throw new IllegalStateException("Stored durable resume command result is corrupt");
        }
        return new LeaseClaimResult(checkpoint, dispatch, idempotentReplay);
    }

    private StoredResumeCommand mapResumeCommand(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredResumeCommand(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("client_request_id"), rs.getString("request_fingerprint"),
                rs.getString("run_id"), rs.getString("expected_owner_id"),
                rs.getLong("expected_lease_epoch"), rs.getLong("expected_revision"),
                rs.getString("expected_checkpoint_fingerprint"),
                rs.getString("claimant_owner_id"), rs.getLong("lease_duration_seconds"),
                rs.getString("authorization_fingerprint"),
                rs.getString("result_checkpoint_fingerprint"),
                rs.getString("result_dispatch_fingerprint"),
                rs.getString("record_fingerprint"),
                rs.getString("result_checkpoint_json"),
                rs.getString("result_dispatch_json"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void requireValidResumeCommandRecord(StoredResumeCommand stored) {
        if (stored.authorizationFingerprint() == null
                || stored.resultDispatchFingerprint() == null
                || stored.resultDispatchJson() == null) {
            throw new IllegalStateException(
                    "Stored durable resume command predates authorization-bound dispatch");
        }
        String actual = ProtocolFingerprint.of(objectMapper, stored.fingerprintMaterial());
        if (!stored.recordFingerprint().equals(actual)) {
            throw new IllegalStateException("Stored durable resume command record is corrupt");
        }
    }

    private String resumeCommandRecordFingerprint(ResumeLeaseCommand command,
                                                  String resultCheckpointFingerprint,
                                                  String resultDispatchFingerprint,
                                                  Instant createdAt) {
        LeaseClaim claim = command.claim();
        Fence fence = claim.expectedFence();
        return ProtocolFingerprint.of(objectMapper, resumeCommandRecordFingerprintMaterial(
                claim.tenantId(), claim.environmentId(), command.clientRequestId(),
                command.requestFingerprint(), claim.runId(), fence.ownerId(), fence.leaseEpoch(),
                fence.revision(), claim.expectedCheckpointFingerprint(), claim.claimantOwnerId(),
                claim.leaseDuration().toSeconds(),
                command.authorization().authorizationFingerprint(),
                resultCheckpointFingerprint, resultDispatchFingerprint, createdAt));
    }

    private static Map<String, Object> resumeCommandRecordFingerprintMaterial(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String expectedOwnerId,
            long expectedLeaseEpoch,
            long expectedRevision,
            String expectedCheckpointFingerprint,
            String claimantOwnerId,
            long leaseDurationSeconds,
            String authorizationFingerprint,
            String resultCheckpointFingerprint,
            String resultDispatchFingerprint,
            Instant createdAt) {
        return Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableResumeCommandRecord.v2"),
                Map.entry("tenantId", tenantId),
                Map.entry("environmentId", environmentId),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("runId", runId),
                Map.entry("expectedOwnerId", expectedOwnerId),
                Map.entry("expectedLeaseEpoch", expectedLeaseEpoch),
                Map.entry("expectedRevision", expectedRevision),
                Map.entry("expectedCheckpointFingerprint", expectedCheckpointFingerprint),
                Map.entry("claimantOwnerId", claimantOwnerId),
                Map.entry("leaseDurationSeconds", leaseDurationSeconds),
                Map.entry("authorizationFingerprint", authorizationFingerprint),
                Map.entry("resultCheckpointFingerprint", resultCheckpointFingerprint),
                Map.entry("resultDispatchFingerprint", resultDispatchFingerprint),
                Map.entry("createdAt", createdAt));
    }

    private void requireClaimable(DurableTestExecutionCheckpoint current,
                                  LeaseClaim claim,
                                  Instant claimedAt) {
        Fence expected = claim.expectedFence();
        var lifecycle = current.lifecycle();
        if (!lifecycle.ownerId().equals(expected.ownerId())
                || lifecycle.leaseEpoch() != expected.leaseEpoch()
                || lifecycle.revision() != expected.revision()
                || !current.checkpointFingerprint().equals(
                claim.expectedCheckpointFingerprint())) {
            throw conflict(STALE_FENCE, "Durable checkpoint fence is stale");
        }
        if (!lifecycle.status().resumable()) {
            throw conflict(NOT_RESUMABLE,
                    "Durable checkpoint lifecycle cannot be resumed");
        }
        if (lifecycle.leaseEpoch() == Long.MAX_VALUE
                || lifecycle.revision() == Long.MAX_VALUE) {
            throw conflict(INVALID_TRANSITION,
                    "Durable checkpoint fence cannot advance without overflow");
        }
        if (lifecycle.leaseExpiresAt().isAfter(claimedAt)) {
            throw conflict(LEASE_ACTIVE, "Durable checkpoint lease is still active");
        }
    }

    private int claimUpdate(DurableTestExecutionCheckpoint claimed,
                            LeaseClaim claim,
                            Instant claimedAt) {
        var lifecycle = claimed.lifecycle();
        Fence expected = claim.expectedFence();
        return jdbc.update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET status = ?, owner_id = ?, lease_epoch = ?, revision = ?,
                    lease_expires_at = ?, checkpoint_fingerprint = ?, updated_at = ?,
                    checkpoint_json = ?
                WHERE run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND owner_id = ? AND lease_epoch = ? AND revision = ?
                  AND checkpoint_fingerprint = ? AND lease_expires_at <= ?
                  AND status IN ('ACTIVE', 'SUSPENDED', 'RESUMING')
                """, lifecycle.status().name(), lifecycle.ownerId(), lifecycle.leaseEpoch(),
                lifecycle.revision(), Timestamp.from(lifecycle.leaseExpiresAt()),
                claimed.checkpointFingerprint(), Timestamp.from(lifecycle.updatedAt()),
                write(claimed), claimed.runId(), claim.tenantId(), claim.environmentId(),
                expected.ownerId(), expected.leaseEpoch(), expected.revision(),
                claim.expectedCheckpointFingerprint(), Timestamp.from(claimedAt));
    }

    private StoredInitialCreation newInitialCreation(
            InitialCreationCommand command, Instant createdAt) {
        Instant leaseExpiresAt = createdAt.plus(command.leaseDuration());
        String fingerprint = initialCreationRecordFingerprint(
                command.scope(), command.clientRequestId(), command.requestFingerprint(),
                command.authorizationFingerprint(), command.proposedRunId(),
                command.proposedEngineExecutionId(), command.claimantOwnerId(), 1,
                createdAt, createdAt, leaseExpiresAt, InitialCreationState.PENDING, "", "");
        return new StoredInitialCreation(
                command.scope().tenantId(), command.scope().environmentId(),
                command.clientRequestId(), command.requestFingerprint(),
                command.authorizationFingerprint(), command.scope().organizationId(),
                command.scope().projectId(), command.scope().actorId(),
                command.proposedRunId(), command.proposedEngineExecutionId(),
                command.claimantOwnerId(), 1, createdAt, createdAt, leaseExpiresAt,
                InitialCreationState.PENDING, "", "", null, fingerprint);
    }

    private void insertInitialCreation(StoredInitialCreation stored) {
        jdbc.update("""
                INSERT INTO rg_test_durable_creation_commands (
                    tenant_id, environment_id, client_request_id, request_fingerprint,
                    authorization_fingerprint, organization_id, project_id, actor_id,
                    run_id, engine_execution_id, owner_id, lease_epoch, created_at,
                    updated_at, lease_expires_at, state, rejection_code,
                    result_checkpoint_fingerprint, result_checkpoint_json, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stored.tenantId(), stored.environmentId(), stored.clientRequestId(),
                stored.requestFingerprint(), stored.authorizationFingerprint(),
                stored.organizationId(), stored.projectId(), stored.actorId(), stored.runId(),
                stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                Timestamp.from(stored.createdAt()), Timestamp.from(stored.updatedAt()),
                Timestamp.from(stored.leaseExpiresAt()), stored.state().name(),
                stored.rejectionCode(), stored.resultCheckpointFingerprint(),
                stored.resultCheckpointJson(), stored.recordFingerprint());
    }

    private int updateInitialCreation(
            StoredInitialCreation next, StoredInitialCreation current) {
        return jdbc.update("""
                UPDATE rg_test_durable_creation_commands
                SET owner_id = ?, lease_epoch = ?, updated_at = ?, lease_expires_at = ?,
                    state = ?, rejection_code = ?, result_checkpoint_fingerprint = ?,
                    result_checkpoint_json = ?, record_fingerprint = ?
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                  AND record_fingerprint = ? AND state = ?
                """, next.ownerId(), next.leaseEpoch(), Timestamp.from(next.updatedAt()),
                Timestamp.from(next.leaseExpiresAt()), next.state().name(),
                next.rejectionCode(), next.resultCheckpointFingerprint(),
                next.resultCheckpointJson(), next.recordFingerprint(), current.tenantId(),
                current.environmentId(), current.clientRequestId(), current.recordFingerprint(),
                current.state().name());
    }

    private List<StoredInitialCreation> findInitialCreations(
            String tenantId,
            String environmentId,
            String clientRequestId,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                        SELECT tenant_id, environment_id, client_request_id,
                               request_fingerprint, authorization_fingerprint,
                               organization_id, project_id, actor_id, run_id,
                               engine_execution_id, owner_id, lease_epoch, created_at,
                               updated_at, lease_expires_at, state, rejection_code,
                               result_checkpoint_fingerprint, result_checkpoint_json,
                               record_fingerprint
                        FROM rg_test_durable_creation_commands
                        WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                        """ + suffix, this::mapInitialCreation,
                tenantId, environmentId, clientRequestId);
    }

    private StoredInitialCreation requireInitialCreation(
            InitialCreationReservation reservation, boolean lock) {
        List<StoredInitialCreation> rows = findInitialCreations(
                reservation.scope().tenantId(), reservation.scope().environmentId(),
                reservation.clientRequestId(), lock);
        if (rows.isEmpty()) {
            throw conflict(STALE_FENCE,
                    "Durable creation reservation no longer exists");
        }
        StoredInitialCreation stored = rows.getFirst();
        requireValidInitialCreationRecord(stored);
        if (!stored.matches(reservation)) {
            throw conflict(IDEMPOTENCY_CONFLICT,
                    "Durable creation reservation intent does not match stored command");
        }
        return stored;
    }

    private void requireLiveInitialCreation(
            StoredInitialCreation stored, Instant observedAt) {
        if (!stored.leaseExpiresAt().isAfter(observedAt)) {
            throw conflict(LEASE_EXPIRED,
                    "Durable creation reservation lease expired before commit");
        }
        if (observedAt.isBefore(stored.updatedAt())) {
            throw new IllegalStateException(
                    "Test-runtime database clock moved behind the creation reservation");
        }
    }

    private static Duration requireCreationLeaseDuration(Duration leaseDuration) {
        Duration required = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (required.compareTo(Duration.ofSeconds(1)) < 0
                || required.compareTo(Duration.ofHours(1)) > 0
                || required.getNano() != 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be whole seconds between one second and one hour");
        }
        return required;
    }

    private void requireInitialCheckpointBinding(
            InitialCreationReservation reservation,
            DurableTestExecutionCheckpoint checkpoint) {
        var lifecycle = checkpoint.lifecycle();
        if (!DurableTestExecutionCheckpoint.SCHEMA_VERSION.equals(
                checkpoint.schemaVersion())
                || !reservation.scope().equals(checkpoint.scope())
                || !reservation.runId().equals(checkpoint.runId())
                || !reservation.engineExecutionId().equals(
                checkpoint.engineExecutionId())
                || lifecycle.status() != DurableTestExecutionCheckpoint.Status.SUSPENDED
                || !reservation.ownerId().equals(lifecycle.ownerId())
                || reservation.leaseEpoch() != lifecycle.leaseEpoch()
                || lifecycle.revision() != 0
                || !reservation.createdAt().equals(lifecycle.createdAt())
                || !reservation.updatedAt().equals(lifecycle.updatedAt())
                || !reservation.leaseExpiresAt().equals(lifecycle.leaseExpiresAt())
                || !"SUSPEND".equals(checkpoint.engineState().boundaryType())
                || checkpoint.engineState().boundarySequence() != 1) {
            throw conflict(INVALID_TRANSITION,
                    "Initial durable checkpoint does not bind its creation reservation");
        }
    }

    private InitialCreationReservationResult initialCreationResult(
            StoredInitialCreation stored,
            boolean acquired,
            boolean idempotentReplay) {
        InitialCreationReservation reservation = toInitialCreationReservation(stored);
        DurableTestExecutionCheckpoint checkpoint = null;
        if (stored.state() == InitialCreationState.COMMITTED) {
            checkpoint = readInitialCreationCheckpoint(stored);
        }
        return new InitialCreationReservationResult(
                reservation, checkpoint, acquired, idempotentReplay);
    }

    private InitialCreationReservation toInitialCreationReservation(
            StoredInitialCreation stored) {
        requireValidInitialCreationRecord(stored);
        return new InitialCreationReservation(
                InitialCreationReservation.SCHEMA_VERSION, stored.scope(),
                stored.clientRequestId(), stored.requestFingerprint(),
                stored.authorizationFingerprint(), stored.runId(),
                stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                stored.createdAt(), stored.updatedAt(), stored.leaseExpiresAt(),
                stored.state(), stored.rejectionCode(),
                stored.resultCheckpointFingerprint(), stored.recordFingerprint());
    }

    private DurableTestExecutionCheckpoint readInitialCreationCheckpoint(
            StoredInitialCreation stored) {
        try {
            DurableTestExecutionCheckpoint checkpoint = objectMapper.readValue(
                    stored.resultCheckpointJson(), DurableTestExecutionCheckpoint.class);
            integrity.requireValid(checkpoint);
            if (!stored.resultCheckpointFingerprint().equals(
                    checkpoint.checkpointFingerprint())
                    || !stored.runId().equals(checkpoint.runId())
                    || !stored.engineExecutionId().equals(
                    checkpoint.engineExecutionId())
                    || !stored.scope().equals(checkpoint.scope())) {
                throw new IllegalArgumentException(
                        "Creation checkpoint does not agree with its command record");
            }
            return checkpoint;
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored durable creation checkpoint is corrupt", corrupt);
        }
    }

    private StoredInitialCreation mapInitialCreation(ResultSet rs, int rowNumber)
            throws SQLException {
        return new StoredInitialCreation(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("client_request_id"), rs.getString("request_fingerprint"),
                rs.getString("authorization_fingerprint"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("actor_id"), rs.getString("run_id"),
                rs.getString("engine_execution_id"), rs.getString("owner_id"),
                rs.getLong("lease_epoch"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("lease_expires_at").toInstant(),
                InitialCreationState.valueOf(rs.getString("state")),
                rs.getString("rejection_code"),
                rs.getString("result_checkpoint_fingerprint"),
                rs.getString("result_checkpoint_json"),
                rs.getString("record_fingerprint"));
    }

    private void requireValidInitialCreationRecord(StoredInitialCreation stored) {
        String actual = initialCreationRecordFingerprint(
                stored.scope(), stored.clientRequestId(), stored.requestFingerprint(),
                stored.authorizationFingerprint(), stored.runId(),
                stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                stored.createdAt(), stored.updatedAt(), stored.leaseExpiresAt(), stored.state(),
                stored.rejectionCode(), stored.resultCheckpointFingerprint());
        if (!stored.recordFingerprint().equals(actual)) {
            throw new IllegalStateException(
                    "Stored durable creation command record is corrupt");
        }
        toInitialCreationValueForValidation(stored);
    }

    private void toInitialCreationValueForValidation(StoredInitialCreation stored) {
        new InitialCreationReservation(
                InitialCreationReservation.SCHEMA_VERSION, stored.scope(),
                stored.clientRequestId(), stored.requestFingerprint(),
                stored.authorizationFingerprint(), stored.runId(),
                stored.engineExecutionId(), stored.ownerId(), stored.leaseEpoch(),
                stored.createdAt(), stored.updatedAt(), stored.leaseExpiresAt(),
                stored.state(), stored.rejectionCode(),
                stored.resultCheckpointFingerprint(), stored.recordFingerprint());
        if (stored.state() == InitialCreationState.COMMITTED
                && (stored.resultCheckpointJson() == null
                || stored.resultCheckpointJson().isBlank())) {
            throw new IllegalStateException(
                    "Committed durable creation result has no checkpoint snapshot");
        }
        if (stored.state() != InitialCreationState.COMMITTED
                && stored.resultCheckpointJson() != null) {
            throw new IllegalStateException(
                    "Non-committed durable creation carries a checkpoint snapshot");
        }
    }

    private String initialCreationRecordFingerprint(
            DurableTestExecutionCheckpoint.Scope scope,
            String clientRequestId,
            String requestFingerprint,
            String authorizationFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            Instant createdAt,
            Instant updatedAt,
            Instant leaseExpiresAt,
            InitialCreationState state,
            String rejectionCode,
            String resultCheckpointFingerprint) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", InitialCreationReservation.SCHEMA_VERSION),
                Map.entry("scope", scope),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("authorizationFingerprint", authorizationFingerprint),
                Map.entry("runId", runId),
                Map.entry("engineExecutionId", engineExecutionId),
                Map.entry("ownerId", ownerId),
                Map.entry("leaseEpoch", leaseEpoch),
                Map.entry("createdAt", createdAt),
                Map.entry("updatedAt", updatedAt),
                Map.entry("leaseExpiresAt", leaseExpiresAt),
                Map.entry("state", state.name()),
                Map.entry("rejectionCode", rejectionCode),
                Map.entry("resultCheckpointFingerprint", resultCheckpointFingerprint)));
    }

    private Instant databaseNow() {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
        if (now == null) {
            throw new IllegalStateException("Test-runtime database did not provide its clock");
        }
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    private Optional<DurableTestExecutionCheckpoint> findInternal(
            String tenantId, String environmentId, String runId) {
        List<StoredRow> rows = jdbc.query(selectColumns() + """
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                        """, this::mapRow, tenantId, environmentId, runId);
        return rows.stream().findFirst().map(this::verifiedCheckpoint);
    }

    private Optional<DurableTestExecutionCheckpoint> findWorkerSchedulingCheckpointForUpdate(
            String tenantId,
            String environmentId,
            String runId) {
        List<StoredRow> rows = jdbc.query(selectColumns() + """
                        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
                        FOR UPDATE
                        """, this::mapRow, tenantId, environmentId, runId);
        return rows.stream().findFirst().map(this::verifiedCheckpoint);
    }

    private boolean durableIdentityExists(String runId, String engineExecutionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_durable_execution_checkpoints
                WHERE run_id = ? OR engine_execution_id = ?
                """, Integer.class, runId, engineExecutionId);
        return count != null && count > 0;
    }

    private void insert(DurableTestExecutionCheckpoint checkpoint) {
        var scope = checkpoint.scope();
        var lifecycle = checkpoint.lifecycle();
        var target = checkpoint.dependencies().target();
        jdbc.update("""
                INSERT INTO rg_test_durable_execution_checkpoints (
                    run_id, engine_execution_id, tenant_id, organization_id, project_id,
                    environment_id, actor_id, status, owner_id, lease_epoch, revision,
                    lease_expires_at, plan_fingerprint, target_kind, target_id,
                    target_fingerprint, fixture_fingerprint,
                    fixture_state_fingerprint, provider_state_fingerprint,
                    engine_state_fingerprint, checkpoint_fingerprint, created_at, updated_at,
                    checkpoint_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, checkpoint.runId(), checkpoint.engineExecutionId(), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.actorId(),
                lifecycle.status().name(), lifecycle.ownerId(), lifecycle.leaseEpoch(),
                lifecycle.revision(), Timestamp.from(lifecycle.leaseExpiresAt()),
                checkpoint.dependencies().plan().planFingerprint(),
                target == null ? null : target.kind(), target == null ? null : target.id(),
                target == null ? null : target.fingerprint(),
                checkpoint.dependencies().fixture().fingerprint(),
                checkpoint.fixtureConsumptionState().stateFingerprint(),
                checkpoint.executionServiceState().snapshotFingerprint(),
                checkpoint.engineState().closureFingerprint(), checkpoint.checkpointFingerprint(),
                Timestamp.from(lifecycle.createdAt()), Timestamp.from(lifecycle.updatedAt()),
                write(checkpoint));
    }

    private int update(DurableTestExecutionCheckpoint next,
                       DurableTestExecutionCheckpoint current,
                       Fence expected) {
        var lifecycle = next.lifecycle();
        int updated = jdbc.update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET status = ?, owner_id = ?, lease_epoch = ?, revision = ?,
                    lease_expires_at = ?, plan_fingerprint = ?, fixture_fingerprint = ?,
                    fixture_state_fingerprint = ?, provider_state_fingerprint = ?,
                    engine_state_fingerprint = ?, checkpoint_fingerprint = ?, updated_at = ?,
                    checkpoint_json = ?
                WHERE run_id = ? AND tenant_id = ? AND environment_id = ?
                  AND owner_id = ? AND lease_epoch = ? AND revision = ?
                  AND checkpoint_fingerprint = ?
                """, lifecycle.status().name(), lifecycle.ownerId(), lifecycle.leaseEpoch(),
                lifecycle.revision(), Timestamp.from(lifecycle.leaseExpiresAt()),
                next.dependencies().plan().planFingerprint(),
                next.dependencies().fixture().fingerprint(),
                next.fixtureConsumptionState().stateFingerprint(),
                next.executionServiceState().snapshotFingerprint(),
                next.engineState().closureFingerprint(), next.checkpointFingerprint(),
                Timestamp.from(lifecycle.updatedAt()), write(next), next.runId(),
                next.scope().tenantId(), next.scope().environmentId(), expected.ownerId(),
                expected.leaseEpoch(), expected.revision(), current.checkpointFingerprint());
        if (updated == 1) {
            clearWorkerCandidateSchedulingState(
                    new WorkerAcquisitionScope(
                            current.scope().tenantId(), current.scope().organizationId(),
                            current.scope().projectId(), current.scope().environmentId()),
                    current.runId(), current.checkpointFingerprint());
        }
        return updated;
    }

    private void requireTransition(DurableTestExecutionCheckpoint current,
                                   DurableTestExecutionCheckpoint next,
                                   Fence expected) {
        var currentLifecycle = current.lifecycle();
        var nextLifecycle = next.lifecycle();
        if (!currentLifecycle.ownerId().equals(expected.ownerId())
                || currentLifecycle.leaseEpoch() != expected.leaseEpoch()
                || currentLifecycle.revision() != expected.revision()) {
            throw conflict(STALE_FENCE, "Durable checkpoint fence is stale");
        }
        if (nextLifecycle.revision() != expected.revision() + 1) {
            throw conflict(INVALID_TRANSITION, "Durable checkpoint must advance exactly one revision");
        }
        if (!nextLifecycle.ownerId().equals(expected.ownerId())
                || nextLifecycle.leaseEpoch() != expected.leaseEpoch()) {
            throw conflict(INVALID_TRANSITION,
                    "Owner transfer requires an explicit future lease-claim protocol");
        }
        if (!current.scope().equals(next.scope())
                || !current.runId().equals(next.runId())
                || !current.engineExecutionId().equals(next.engineExecutionId())
                || !current.dependencies().equals(next.dependencies())
                || !currentLifecycle.createdAt().equals(nextLifecycle.createdAt())) {
            throw conflict(INVALID_TRANSITION,
                    "Durable checkpoint immutable identity or dependency closure changed");
        }
        boolean explicitRecoveryRelease =
                currentLifecycle.status() == DurableTestExecutionCheckpoint.Status.RESUMING
                        && nextLifecycle.status()
                        == DurableTestExecutionCheckpoint.Status.SUSPENDED
                        && nextLifecycle.leaseExpiresAt().equals(nextLifecycle.updatedAt());
        if (nextLifecycle.updatedAt().isBefore(currentLifecycle.updatedAt())
                || (nextLifecycle.leaseExpiresAt().isBefore(
                currentLifecycle.leaseExpiresAt()) && !explicitRecoveryRelease)) {
            throw conflict(INVALID_TRANSITION, "Durable checkpoint time or lease moved backwards");
        }
        if (!allowed(currentLifecycle.status(), nextLifecycle.status())) {
            throw conflict(INVALID_TRANSITION, "Durable checkpoint status transition is not allowed");
        }
        if (next.engineState().stateVersion() <= current.engineState().stateVersion()
                || next.engineState().boundarySequence() < current.engineState().boundarySequence()) {
            throw conflict(INVALID_TRANSITION, "BLOGE engine checkpoint state moved backwards");
        }
        requireMonotonicCounters(current.fixtureConsumptionState().ruleUses(),
                next.fixtureConsumptionState().ruleUses(), "fixture rule consumption");
        requireMonotonicCounters(current.fixtureConsumptionState().siteOccurrenceCursors(),
                next.fixtureConsumptionState().siteOccurrenceCursors(), "site occurrence");
        requireMonotonicCounters(current.fixtureConsumptionState().graphOccurrenceCursors(),
                next.fixtureConsumptionState().graphOccurrenceCursors(), "graph occurrence");
        requireMonotonicProviderState(current.executionServiceState(), next.executionServiceState());
    }

    private static boolean allowed(DurableTestExecutionCheckpoint.Status current,
                                   DurableTestExecutionCheckpoint.Status next) {
        return switch (current) {
            case ACTIVE -> Set.of(DurableTestExecutionCheckpoint.Status.ACTIVE,
                    DurableTestExecutionCheckpoint.Status.SUSPENDED,
                    DurableTestExecutionCheckpoint.Status.TERMINAL,
                    DurableTestExecutionCheckpoint.Status.CONTROL_PLAN_UNAVAILABLE).contains(next);
            case SUSPENDED -> Set.of(DurableTestExecutionCheckpoint.Status.SUSPENDED,
                    DurableTestExecutionCheckpoint.Status.RESUMING,
                    DurableTestExecutionCheckpoint.Status.TERMINAL,
                    DurableTestExecutionCheckpoint.Status.CONTROL_PLAN_UNAVAILABLE).contains(next);
            case RESUMING -> Set.of(DurableTestExecutionCheckpoint.Status.RESUMING,
                    DurableTestExecutionCheckpoint.Status.ACTIVE,
                    DurableTestExecutionCheckpoint.Status.SUSPENDED,
                    DurableTestExecutionCheckpoint.Status.TERMINAL,
                    DurableTestExecutionCheckpoint.Status.CONTROL_PLAN_UNAVAILABLE).contains(next);
            case TERMINAL, CONTROL_PLAN_UNAVAILABLE -> false;
        };
    }

    private static void requireMonotonicProviderState(ExecutionServiceStateSnapshot current,
                                                      ExecutionServiceStateSnapshot next) {
        if (!current.planFingerprint().equals(next.planFingerprint())
                || !current.bindingSetFingerprint().equals(next.bindingSetFingerprint())) {
            throw conflict(INVALID_TRANSITION, "Execution-service plan or binding set changed");
        }
        if (current.logicalTime() != null && (next.logicalTime() == null
                || next.logicalTime().isBefore(current.logicalTime()))) {
            throw conflict(INVALID_TRANSITION, "Logical time moved backwards");
        }
        requireMonotonicCounters(current.randomScopeCursors(), next.randomScopeCursors(),
                "random provider cursor");
        requireMonotonicCounters(current.uuidScopeCursors(), next.uuidScopeCursors(),
                "UUID provider cursor");
        Map<String, ExecutionServiceStateSnapshot.UsageState> usages = new HashMap<>();
        next.usages().forEach(usage -> usages.put(usage.service(), usage));
        for (ExecutionServiceStateSnapshot.UsageState previous : current.usages()) {
            ExecutionServiceStateSnapshot.UsageState candidate = usages.get(previous.service());
            if (candidate == null
                    || candidate.providerCalls() < previous.providerCalls()
                    || candidate.semanticProviderCalls() < previous.semanticProviderCalls()
                    || candidate.functionCalls() < previous.functionCalls()
                    || !candidate.functionCallSites().containsAll(previous.functionCallSites())
                    || !candidate.providerScopeFingerprints()
                    .containsAll(previous.providerScopeFingerprints())) {
                throw conflict(INVALID_TRANSITION, "Execution-service usage moved backwards");
            }
        }
    }

    private static void requireMonotonicCounters(Map<String, ? extends Number> current,
                                                 Map<String, ? extends Number> next,
                                                 String field) {
        current.forEach((key, previous) -> {
            Number candidate = next.get(key);
            if (candidate == null || candidate.longValue() < previous.longValue()) {
                throw conflict(INVALID_TRANSITION, field + " cursor moved backwards");
            }
        });
    }

    private void requireSealed(DurableTestExecutionCheckpoint checkpoint) {
        integrity.requireValid(Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    private static void requireMutationBinding(
            DurableTestExecutionCheckpoint checkpoint,
            BoundEngineStateMutation mutation) {
        if (!checkpoint.engineExecutionId().equals(mutation.engineExecutionId())
                || !checkpoint.engineState().equals(mutation.engineState())) {
            throw conflict(INVALID_TRANSITION,
                    "Durable checkpoint identity or engine state does not match its engine-state mutation");
        }
    }

    private DurableTestExecutionCheckpoint verifiedCheckpoint(StoredRow row) {
        try {
            DurableTestExecutionCheckpoint checkpoint = objectMapper.readValue(
                    row.checkpointJson(), DurableTestExecutionCheckpoint.class);
            integrity.requireValid(checkpoint);
            if (!row.agreesWith(checkpoint)) {
                throw new IllegalStateException("Stored durable test execution checkpoint is corrupt");
            }
            return checkpoint;
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored durable test execution checkpoint is corrupt", corrupt);
        }
    }

    private StoredRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredRow(
                rs.getString("run_id"), rs.getString("engine_execution_id"),
                rs.getString("tenant_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("environment_id"),
                rs.getString("actor_id"), rs.getString("status"), rs.getString("owner_id"),
                rs.getLong("lease_epoch"), rs.getLong("revision"),
                rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getString("plan_fingerprint"), rs.getString("target_kind"),
                rs.getString("target_id"), rs.getString("target_fingerprint"),
                rs.getString("fixture_fingerprint"),
                rs.getString("fixture_state_fingerprint"),
                rs.getString("provider_state_fingerprint"),
                rs.getString("engine_state_fingerprint"),
                rs.getString("checkpoint_fingerprint"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("checkpoint_json"));
    }

    private static String selectColumns() {
        return """
                SELECT run_id, engine_execution_id, tenant_id, organization_id, project_id,
                       environment_id, actor_id, status, owner_id, lease_epoch, revision,
                       lease_expires_at, plan_fingerprint, target_kind, target_id,
                       target_fingerprint, fixture_fingerprint,
                       fixture_state_fingerprint, provider_state_fingerprint,
                       engine_state_fingerprint, checkpoint_fingerprint, created_at, updated_at,
                       checkpoint_json
                FROM rg_test_durable_execution_checkpoints
                """;
    }

    private String write(DurableTestExecutionCheckpoint checkpoint) {
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize durable test execution checkpoint", failure);
        }
    }

    private String writeDispatch(DurableTestRecoveryDispatch dispatch) {
        try {
            return objectMapper.writeValueAsString(dispatch);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize durable recovery dispatch", failure);
        }
    }

    private String writeTerminalReceipt(DurableTestRecoveryTerminalReceipt receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize durable recovery terminal receipt", failure);
        }
    }

    private String writeEvidenceGaps(List<String> evidenceGaps) {
        try {
            return objectMapper.writeValueAsString(evidenceGaps);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot serialize durable recovery-step evidence gaps", failure);
        }
    }

    private List<String> readEvidenceGaps(String json) {
        try {
            List<String> result = objectMapper.readValue(
                    json, objectMapper.getTypeFactory().constructCollectionType(
                            List.class, String.class));
            List<String> canonical = result == null ? List.of() : result.stream()
                    .map(value -> value == null ? "" : value.trim().toUpperCase(
                            java.util.Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
            if (result == null || result.isEmpty() || result.size() > 32
                    || canonical.size() != result.size()
                    || !canonical.equals(result)
                    || canonical.stream().anyMatch(value ->
                    !value.matches("[A-Z][A-Z0-9_.-]{0,127}"))) {
                throw new IllegalArgumentException(
                        "Recovery-step evidence gaps must be a canonical bounded list");
            }
            return canonical;
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored recovery-step evidence gaps are corrupt", corrupt);
        }
    }

    private static DurableTestExecutionCheckpointConflictException conflict(
            DurableTestExecutionCheckpointConflictException.Reason reason, String message) {
        return new DurableTestExecutionCheckpointConflictException(reason, message);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizedEnvironment(String value) {
        return normalized(value).toLowerCase(java.util.Locale.ROOT);
    }

    private record WorkerScanPosition(
            Instant leaseExpiresAt,
            Instant updatedAt,
            String runId) implements Comparable<WorkerScanPosition> {
        private WorkerScanPosition {
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            runId = normalized(runId);
            if (runId.isBlank()) {
                throw new IllegalArgumentException("Worker scan runId is required");
            }
        }

        @Override
        public int compareTo(WorkerScanPosition other) {
            int leaseOrder = leaseExpiresAt.compareTo(other.leaseExpiresAt);
            if (leaseOrder != 0) {
                return leaseOrder;
            }
            int updateOrder = updatedAt.compareTo(other.updatedAt);
            return updateOrder != 0 ? updateOrder : runId.compareTo(other.runId);
        }
    }

    private record WorkerScanCursorSnapshot(
            long cycleEpoch,
            WorkerScanPosition position,
            String cursorFingerprint) {
    }

    private record StoredWorkerScanCursor(
            String scopeKey,
            String tenantId,
            String environmentId,
            String organizationId,
            String projectId,
            long cycleEpoch,
            Instant cursorLeaseExpiresAt,
            Instant cursorUpdatedAt,
            String cursorRunId,
            Instant advancedAt,
            String cursorFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private WorkerScanPosition position() {
            return new WorkerScanPosition(
                    cursorLeaseExpiresAt, cursorUpdatedAt, cursorRunId);
        }

        private WorkerScanCursorSnapshot snapshot() {
            return new WorkerScanCursorSnapshot(
                    cycleEpoch, position(), cursorFingerprint);
        }
    }

    private record StoredWorkerCandidateDeferral(
            String scopeKey,
            String tenantId,
            String environmentId,
            String organizationId,
            String projectId,
            String runId,
            String checkpointFingerprint,
            String reason,
            long consecutiveFailures,
            Instant firstObservedAt,
            Instant lastObservedAt,
            Instant retryAfter,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private ActiveWorkerCandidateDeferral activeDeferral() {
            return new ActiveWorkerCandidateDeferral(
                    WorkerCandidateDeferralReason.valueOf(reason), consecutiveFailures,
                    firstObservedAt, lastObservedAt, retryAfter);
        }
    }

    private record StoredWorkerCandidateQuarantine(
            String scopeKey,
            String tenantId,
            String environmentId,
            String organizationId,
            String projectId,
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

        private ActiveWorkerCandidateQuarantine activeQuarantine() {
            return new ActiveWorkerCandidateQuarantine(
                    WorkerCandidateDeferralReason.valueOf(reason), consecutiveFailures,
                    quarantineThreshold, firstObservedAt, quarantinedAt);
        }
    }

    private record StoredWorkerAcquisition(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String organizationId,
            String projectId,
            WorkerAcquisitionOutcome outcome,
            Instant observedAt,
            String runId,
            String resultCheckpointFingerprint,
            String resultDispatchFingerprint,
            String resultCheckpointJson,
            String resultDispatchJson,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }
    }

    private record StoredRow(
            String runId,
            String engineExecutionId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String actorId,
            String status,
            String ownerId,
            long leaseEpoch,
            long revision,
            Instant leaseExpiresAt,
            String planFingerprint,
            String targetKind,
            String targetId,
            String targetFingerprint,
            String fixtureFingerprint,
            String fixtureStateFingerprint,
            String providerStateFingerprint,
            String engineStateFingerprint,
            String checkpointFingerprint,
            Instant createdAt,
            Instant updatedAt,
            String checkpointJson
    ) {
        private boolean agreesWith(DurableTestExecutionCheckpoint checkpoint) {
            var scope = checkpoint.scope();
            var lifecycle = checkpoint.lifecycle();
            return runId.equals(checkpoint.runId())
                    && engineExecutionId.equals(checkpoint.engineExecutionId())
                    && tenantId.equals(scope.tenantId())
                    && organizationId.equals(scope.organizationId())
                    && projectId.equals(scope.projectId())
                    && environmentId.equals(scope.environmentId())
                    && actorId.equals(scope.actorId())
                    && status.equals(lifecycle.status().name())
                    && ownerId.equals(lifecycle.ownerId())
                    && leaseEpoch == lifecycle.leaseEpoch()
                    && revision == lifecycle.revision()
                    && leaseExpiresAt.equals(lifecycle.leaseExpiresAt())
                    && planFingerprint.equals(checkpoint.dependencies().plan().planFingerprint())
                    && targetAgreesWith(checkpoint)
                    && fixtureFingerprint.equals(checkpoint.dependencies().fixture().fingerprint())
                    && fixtureStateFingerprint.equals(
                    checkpoint.fixtureConsumptionState().stateFingerprint())
                    && providerStateFingerprint.equals(
                    checkpoint.executionServiceState().snapshotFingerprint())
                    && engineStateFingerprint.equals(checkpoint.engineState().closureFingerprint())
                    && checkpointFingerprint.equals(checkpoint.checkpointFingerprint())
                    && createdAt.equals(lifecycle.createdAt())
                    && updatedAt.equals(lifecycle.updatedAt());
        }

        private boolean targetAgreesWith(DurableTestExecutionCheckpoint checkpoint) {
            var target = checkpoint.dependencies().target();
            if (target == null) {
                return targetKind == null && targetId == null && targetFingerprint == null;
            }
            return target.kind().equals(targetKind)
                    && target.id().equals(targetId)
                    && target.fingerprint().equals(targetFingerprint);
        }
    }

    private record StoredInitialCreation(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String authorizationFingerprint,
            String organizationId,
            String projectId,
            String actorId,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            Instant createdAt,
            Instant updatedAt,
            Instant leaseExpiresAt,
            InitialCreationState state,
            String rejectionCode,
            String resultCheckpointFingerprint,
            String resultCheckpointJson,
            String recordFingerprint) {

        private DurableTestExecutionCheckpoint.Scope scope() {
            return new DurableTestExecutionCheckpoint.Scope(
                    tenantId, organizationId, projectId, environmentId, actorId);
        }

        private boolean matches(InitialCreationCommand command) {
            return scope().equals(command.scope())
                    && clientRequestId.equals(command.clientRequestId())
                    && requestFingerprint.equals(command.requestFingerprint())
                    && authorizationFingerprint.equals(
                    command.authorizationFingerprint());
        }

        private boolean matches(InitialCreationReservation reservation) {
            return scope().equals(reservation.scope())
                    && clientRequestId.equals(reservation.clientRequestId())
                    && requestFingerprint.equals(reservation.requestFingerprint())
                    && authorizationFingerprint.equals(
                    reservation.authorizationFingerprint())
                    && runId.equals(reservation.runId())
                    && engineExecutionId.equals(reservation.engineExecutionId());
        }

        private StoredInitialCreation acquired(
                String nextOwnerId,
                long nextLeaseEpoch,
                Instant nextUpdatedAt,
                Instant nextLeaseExpiresAt,
                String nextRecordFingerprint) {
            return new StoredInitialCreation(
                    tenantId, environmentId, clientRequestId, requestFingerprint,
                    authorizationFingerprint, organizationId, projectId, actorId, runId,
                    engineExecutionId, nextOwnerId, nextLeaseEpoch, createdAt,
                    nextUpdatedAt, nextLeaseExpiresAt, InitialCreationState.PENDING,
                    "", "", null, nextRecordFingerprint);
        }

        private StoredInitialCreation renewed(
                Instant nextUpdatedAt,
                Instant nextLeaseExpiresAt,
                String nextRecordFingerprint) {
            return new StoredInitialCreation(
                    tenantId, environmentId, clientRequestId, requestFingerprint,
                    authorizationFingerprint, organizationId, projectId, actorId, runId,
                    engineExecutionId, ownerId, leaseEpoch, createdAt, nextUpdatedAt,
                    nextLeaseExpiresAt, InitialCreationState.PENDING,
                    "", "", null, nextRecordFingerprint);
        }

        private StoredInitialCreation committed(
                String checkpointFingerprint,
                String checkpointJson,
                Instant nextUpdatedAt,
                String nextRecordFingerprint) {
            return new StoredInitialCreation(
                    tenantId, environmentId, clientRequestId, requestFingerprint,
                    authorizationFingerprint, organizationId, projectId, actorId, runId,
                    engineExecutionId, ownerId, leaseEpoch, createdAt, nextUpdatedAt,
                    leaseExpiresAt, InitialCreationState.COMMITTED, "",
                    checkpointFingerprint, checkpointJson, nextRecordFingerprint);
        }

        private StoredInitialCreation rejected(
                String nextRejectionCode,
                Instant nextUpdatedAt,
                String nextRecordFingerprint) {
            return new StoredInitialCreation(
                    tenantId, environmentId, clientRequestId, requestFingerprint,
                    authorizationFingerprint, organizationId, projectId, actorId, runId,
                    engineExecutionId, ownerId, leaseEpoch, createdAt, nextUpdatedAt,
                    leaseExpiresAt, InitialCreationState.REJECTED, nextRejectionCode,
                    "", null, nextRecordFingerprint);
        }
    }

    private record StoredResumeCommand(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String expectedOwnerId,
            long expectedLeaseEpoch,
            long expectedRevision,
            String expectedCheckpointFingerprint,
            String claimantOwnerId,
            long leaseDurationSeconds,
            String authorizationFingerprint,
            String resultCheckpointFingerprint,
            String resultDispatchFingerprint,
            String recordFingerprint,
            String resultCheckpointJson,
            String resultDispatchJson,
            Instant createdAt
    ) {
        private Map<String, Object> fingerprintMaterial() {
            return resumeCommandRecordFingerprintMaterial(
                    tenantId, environmentId, clientRequestId, requestFingerprint, runId,
                    expectedOwnerId, expectedLeaseEpoch, expectedRevision,
                    expectedCheckpointFingerprint, claimantOwnerId, leaseDurationSeconds,
                    authorizationFingerprint, resultCheckpointFingerprint,
                    resultDispatchFingerprint, createdAt);
        }

        private boolean matches(ResumeLeaseCommand command) {
            LeaseClaim claim = command.claim();
            Fence fence = claim.expectedFence();
            return tenantId.equals(claim.tenantId())
                    && environmentId.equals(claim.environmentId())
                    && clientRequestId.equals(command.clientRequestId())
                    && requestFingerprint.equals(command.requestFingerprint())
                    && runId.equals(claim.runId())
                    && expectedOwnerId.equals(fence.ownerId())
                    && expectedLeaseEpoch == fence.leaseEpoch()
                    && expectedRevision == fence.revision()
                    && expectedCheckpointFingerprint.equals(
                    claim.expectedCheckpointFingerprint())
                    && claimantOwnerId.equals(claim.claimantOwnerId())
                    && leaseDurationSeconds == claim.leaseDuration().toSeconds()
                    && authorizationFingerprint.equals(
                    command.authorization().authorizationFingerprint());
        }

        private boolean agreesWith(
                DurableTestExecutionCheckpoint checkpoint,
                DurableTestRecoveryDispatch dispatch) {
            return resultCheckpointFingerprint.equals(checkpoint.checkpointFingerprint())
                    && resultDispatchFingerprint.equals(dispatch.dispatchFingerprint())
                    && authorizationFingerprint.equals(
                    dispatch.authorization().authorizationFingerprint())
                    && expectedCheckpointFingerprint.equals(
                    dispatch.authorization().sourceCheckpointFingerprint())
                    && tenantId.equals(checkpoint.scope().tenantId())
                    && environmentId.equals(checkpoint.scope().environmentId())
                    && runId.equals(checkpoint.runId())
                    && claimantOwnerId.equals(checkpoint.lifecycle().ownerId())
                    && checkpoint.lifecycle().leaseEpoch() > 1
                    && expectedLeaseEpoch == checkpoint.lifecycle().leaseEpoch() - 1
                    && checkpoint.lifecycle().revision() > 0
                    && expectedRevision == checkpoint.lifecycle().revision() - 1
                    && checkpoint.lifecycle().status()
                    == DurableTestExecutionCheckpoint.Status.RESUMING;
        }
    }

    private record StoredRecoveryHeartbeat(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            long expectedRevision,
            Instant expectedLeaseExpiresAt,
            String expectedCheckpointFingerprint,
            String expectedDispatchFingerprint,
            String authorizationFingerprint,
            long leaseDurationSeconds,
            long resultRevision,
            Instant resultLeaseExpiresAt,
            String resultCheckpointFingerprint,
            String resultDispatchFingerprint,
            String recordFingerprint,
            String resultCheckpointJson,
            String resultDispatchJson,
            Instant createdAt
    ) {
        private Map<String, Object> fingerprintMaterial() {
            return recoveryHeartbeatFingerprintMaterial(
                    tenantId, environmentId, clientRequestId, requestFingerprint, runId,
                    engineExecutionId, ownerId, leaseEpoch, expectedRevision,
                    expectedLeaseExpiresAt, expectedCheckpointFingerprint,
                    expectedDispatchFingerprint, authorizationFingerprint, leaseDurationSeconds,
                    resultRevision, resultLeaseExpiresAt, resultCheckpointFingerprint,
                    resultDispatchFingerprint, createdAt);
        }

        private boolean matches(RecoveryHeartbeatCommand command) {
            DurableTestRecoveryDispatch source = command.expectedDispatch();
            return tenantId.equals(source.scope().tenantId())
                    && environmentId.equals(source.scope().environmentId())
                    && clientRequestId.equals(command.clientRequestId())
                    && requestFingerprint.equals(command.requestFingerprint())
                    && runId.equals(source.runId())
                    && engineExecutionId.equals(source.engineExecutionId())
                    && ownerId.equals(source.ownerId())
                    && leaseEpoch == source.leaseEpoch()
                    && expectedRevision == source.revision()
                    && expectedLeaseExpiresAt.equals(source.leaseExpiresAt())
                    && expectedCheckpointFingerprint.equals(source.checkpointFingerprint())
                    && expectedDispatchFingerprint.equals(source.dispatchFingerprint())
                    && authorizationFingerprint.equals(
                    source.authorization().authorizationFingerprint())
                    && leaseDurationSeconds == command.leaseDuration().toSeconds();
        }

        private boolean agreesWith(
                DurableTestExecutionCheckpoint checkpoint,
                DurableTestRecoveryDispatch dispatch) {
            DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
            return tenantId.equals(checkpoint.scope().tenantId())
                    && environmentId.equals(checkpoint.scope().environmentId())
                    && runId.equals(checkpoint.runId())
                    && engineExecutionId.equals(checkpoint.engineExecutionId())
                    && ownerId.equals(lifecycle.ownerId())
                    && leaseEpoch == lifecycle.leaseEpoch()
                    && resultRevision == lifecycle.revision()
                    && expectedRevision < Long.MAX_VALUE
                    && resultRevision == expectedRevision + 1
                    && resultLeaseExpiresAt.equals(lifecycle.leaseExpiresAt())
                    && resultCheckpointFingerprint.equals(
                    checkpoint.checkpointFingerprint())
                    && resultDispatchFingerprint.equals(dispatch.dispatchFingerprint())
                    && authorizationFingerprint.equals(
                    dispatch.authorization().authorizationFingerprint())
                    && lifecycle.status() == DurableTestExecutionCheckpoint.Status.RESUMING;
        }
    }

    private record StoredRecoveryStep(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            long expectedRevision,
            Instant expectedLeaseExpiresAt,
            String expectedCheckpointFingerprint,
            String expectedDispatchFingerprint,
            String authorizationFingerprint,
            String boundaryOutcome,
            String engineStateFingerprint,
            String fixtureStateFingerprint,
            String providerStateFingerprint,
            String evidenceGapsFingerprint,
            String evidenceGapsJson,
            long resultRevision,
            String resultCheckpointFingerprint,
            String resultReceiptFingerprint,
            String recordFingerprint,
            String resultCheckpointJson,
            String resultReceiptJson,
            Instant createdAt
    ) {
        private Map<String, Object> fingerprintMaterial() {
            return recoveryStepFingerprintMaterial(
                    tenantId, environmentId, clientRequestId, requestFingerprint, runId,
                    engineExecutionId, ownerId, leaseEpoch, expectedRevision,
                    expectedLeaseExpiresAt, expectedCheckpointFingerprint,
                    expectedDispatchFingerprint, authorizationFingerprint, boundaryOutcome,
                    engineStateFingerprint, fixtureStateFingerprint, providerStateFingerprint,
                    evidenceGapsFingerprint, resultRevision, resultCheckpointFingerprint,
                    resultReceiptFingerprint, createdAt);
        }

        private boolean matches(
                RecoveryStepCommand command, String commandEvidenceGapsFingerprint) {
            DurableTestRecoveryDispatch source = command.expectedDispatch();
            return tenantId.equals(source.scope().tenantId())
                    && environmentId.equals(source.scope().environmentId())
                    && clientRequestId.equals(command.clientRequestId())
                    && requestFingerprint.equals(command.requestFingerprint())
                    && runId.equals(source.runId())
                    && engineExecutionId.equals(source.engineExecutionId())
                    && ownerId.equals(source.ownerId())
                    && leaseEpoch == source.leaseEpoch()
                    && expectedRevision == source.revision()
                    && expectedLeaseExpiresAt.equals(source.leaseExpiresAt())
                    && expectedCheckpointFingerprint.equals(source.checkpointFingerprint())
                    && expectedDispatchFingerprint.equals(source.dispatchFingerprint())
                    && authorizationFingerprint.equals(
                    source.authorization().authorizationFingerprint())
                    && boundaryOutcome.equals(command.outcome().name())
                    && engineStateFingerprint.equals(
                    command.engineState().closureFingerprint())
                    && fixtureStateFingerprint.equals(
                    command.fixtureConsumptionState().stateFingerprint())
                    && providerStateFingerprint.equals(
                    command.executionServiceState().snapshotFingerprint())
                    && evidenceGapsFingerprint.equals(commandEvidenceGapsFingerprint);
        }

        private boolean agreesWithSource(DurableTestRecoveryDispatch source) {
            return tenantId.equals(source.scope().tenantId())
                    && environmentId.equals(source.scope().environmentId())
                    && runId.equals(source.runId())
                    && engineExecutionId.equals(source.engineExecutionId())
                    && ownerId.equals(source.ownerId())
                    && leaseEpoch == source.leaseEpoch()
                    && expectedRevision == source.revision()
                    && expectedLeaseExpiresAt.equals(source.leaseExpiresAt())
                    && expectedCheckpointFingerprint.equals(source.checkpointFingerprint())
                    && expectedDispatchFingerprint.equals(source.dispatchFingerprint())
                    && authorizationFingerprint.equals(
                    source.authorization().authorizationFingerprint());
        }

        private boolean agreesWith(
                DurableTestExecutionCheckpoint checkpoint,
                DurableTestRecoveryTerminalReceipt receipt,
                String actualEvidenceGapsFingerprint) {
            DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
            RecoveryStepOutcome outcome;
            try {
                outcome = RecoveryStepOutcome.valueOf(boundaryOutcome);
            } catch (IllegalArgumentException corrupt) {
                return false;
            }
            boolean terminalShape = outcome.terminal()
                    && receipt != null
                    && !resultReceiptFingerprint.isBlank()
                    && resultReceiptJson != null
                    && resultReceiptFingerprint.equals(receipt.receiptFingerprint())
                    && expectedCheckpointFingerprint.equals(
                    receipt.sourceCheckpointFingerprint())
                    && expectedDispatchFingerprint.equals(
                    receipt.sourceDispatchFingerprint())
                    && authorizationFingerprint.equals(
                    receipt.authorization().authorizationFingerprint())
                    && outcome.terminalOutcome() == receipt.executionOutcome()
                    && createdAt.equals(receipt.completedAt())
                    && lifecycle.status() == DurableTestExecutionCheckpoint.Status.TERMINAL;
            boolean suspendedShape = outcome == RecoveryStepOutcome.SUSPENDED
                    && receipt == null
                    && resultReceiptFingerprint.isBlank()
                    && resultReceiptJson == null
                    && lifecycle.status() == DurableTestExecutionCheckpoint.Status.SUSPENDED
                    && lifecycle.leaseExpiresAt().equals(createdAt);
            return tenantId.equals(checkpoint.scope().tenantId())
                    && environmentId.equals(checkpoint.scope().environmentId())
                    && runId.equals(checkpoint.runId())
                    && engineExecutionId.equals(checkpoint.engineExecutionId())
                    && ownerId.equals(lifecycle.ownerId())
                    && leaseEpoch == lifecycle.leaseEpoch()
                    && expectedRevision < Long.MAX_VALUE
                    && resultRevision == expectedRevision + 1
                    && resultRevision == lifecycle.revision()
                    && engineStateFingerprint.equals(
                    checkpoint.engineState().closureFingerprint())
                    && fixtureStateFingerprint.equals(
                    checkpoint.fixtureConsumptionState().stateFingerprint())
                    && providerStateFingerprint.equals(
                    checkpoint.executionServiceState().snapshotFingerprint())
                    && evidenceGapsFingerprint.equals(actualEvidenceGapsFingerprint)
                    && resultCheckpointFingerprint.equals(
                    checkpoint.checkpointFingerprint())
                    && createdAt.equals(lifecycle.updatedAt())
                    && (terminalShape || suspendedShape);
        }
    }

    private record StoredRecoveryTerminal(
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            String runId,
            String engineExecutionId,
            String ownerId,
            long leaseEpoch,
            long expectedRevision,
            Instant expectedLeaseExpiresAt,
            String expectedCheckpointFingerprint,
            String expectedDispatchFingerprint,
            String authorizationFingerprint,
            String executionOutcome,
            String terminalEngineStateFingerprint,
            String terminalFixtureStateFingerprint,
            String terminalProviderStateFingerprint,
            String evidenceGapsFingerprint,
            long resultRevision,
            String resultCheckpointFingerprint,
            String resultReceiptFingerprint,
            String recordFingerprint,
            String resultCheckpointJson,
            String resultReceiptJson,
            Instant createdAt
    ) {
        private Map<String, Object> fingerprintMaterial() {
            return recoveryTerminalFingerprintMaterial(
                    tenantId, environmentId, clientRequestId, requestFingerprint, runId,
                    engineExecutionId, ownerId, leaseEpoch, expectedRevision,
                    expectedLeaseExpiresAt, expectedCheckpointFingerprint,
                    expectedDispatchFingerprint, authorizationFingerprint, executionOutcome,
                    terminalEngineStateFingerprint, terminalFixtureStateFingerprint,
                    terminalProviderStateFingerprint, evidenceGapsFingerprint, resultRevision,
                    resultCheckpointFingerprint, resultReceiptFingerprint, createdAt);
        }

        private boolean matches(
                RecoveryTerminalCommand command, String commandEvidenceGapsFingerprint) {
            DurableTestRecoveryDispatch source = command.expectedDispatch();
            return tenantId.equals(source.scope().tenantId())
                    && environmentId.equals(source.scope().environmentId())
                    && clientRequestId.equals(command.clientRequestId())
                    && requestFingerprint.equals(command.requestFingerprint())
                    && runId.equals(source.runId())
                    && engineExecutionId.equals(source.engineExecutionId())
                    && ownerId.equals(source.ownerId())
                    && leaseEpoch == source.leaseEpoch()
                    && expectedRevision == source.revision()
                    && expectedLeaseExpiresAt.equals(source.leaseExpiresAt())
                    && expectedCheckpointFingerprint.equals(source.checkpointFingerprint())
                    && expectedDispatchFingerprint.equals(source.dispatchFingerprint())
                    && authorizationFingerprint.equals(
                    source.authorization().authorizationFingerprint())
                    && executionOutcome.equals(command.executionOutcome().name())
                    && terminalEngineStateFingerprint.equals(
                    command.terminalEngineState().closureFingerprint())
                    && terminalFixtureStateFingerprint.equals(
                    command.fixtureConsumptionState().stateFingerprint())
                    && terminalProviderStateFingerprint.equals(
                    command.executionServiceState().snapshotFingerprint())
                    && evidenceGapsFingerprint.equals(commandEvidenceGapsFingerprint);
        }

        private boolean agreesWith(
                DurableTestExecutionCheckpoint checkpoint,
                DurableTestRecoveryTerminalReceipt receipt,
                String receiptEvidenceGapsFingerprint) {
            var lifecycle = checkpoint.lifecycle();
            return tenantId.equals(checkpoint.scope().tenantId())
                    && environmentId.equals(checkpoint.scope().environmentId())
                    && runId.equals(checkpoint.runId())
                    && engineExecutionId.equals(checkpoint.engineExecutionId())
                    && ownerId.equals(lifecycle.ownerId())
                    && leaseEpoch == lifecycle.leaseEpoch()
                    && expectedRevision < Long.MAX_VALUE
                    && resultRevision == expectedRevision + 1
                    && resultRevision == lifecycle.revision()
                    && expectedCheckpointFingerprint.equals(
                    receipt.sourceCheckpointFingerprint())
                    && expectedDispatchFingerprint.equals(
                    receipt.sourceDispatchFingerprint())
                    && authorizationFingerprint.equals(
                    receipt.authorization().authorizationFingerprint())
                    && executionOutcome.equals(receipt.executionOutcome().name())
                    && terminalEngineStateFingerprint.equals(
                    checkpoint.engineState().closureFingerprint())
                    && terminalFixtureStateFingerprint.equals(
                    checkpoint.fixtureConsumptionState().stateFingerprint())
                    && terminalProviderStateFingerprint.equals(
                    checkpoint.executionServiceState().snapshotFingerprint())
                    && evidenceGapsFingerprint.equals(receiptEvidenceGapsFingerprint)
                    && resultCheckpointFingerprint.equals(
                    checkpoint.checkpointFingerprint())
                    && resultReceiptFingerprint.equals(receipt.receiptFingerprint())
                    && createdAt.equals(receipt.completedAt())
                    && lifecycle.status() == DurableTestExecutionCheckpoint.Status.TERMINAL;
        }
    }
}
