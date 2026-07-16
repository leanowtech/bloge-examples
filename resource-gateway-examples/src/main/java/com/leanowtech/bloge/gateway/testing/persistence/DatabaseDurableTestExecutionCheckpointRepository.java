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
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the isolated durable control table and its scoped execution lookup index. */
    @PostConstruct
    public void init() {
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
        return jdbc.update("""
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
        if (nextLifecycle.updatedAt().isBefore(currentLifecycle.updatedAt())
                || nextLifecycle.leaseExpiresAt().isBefore(currentLifecycle.leaseExpiresAt())) {
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
