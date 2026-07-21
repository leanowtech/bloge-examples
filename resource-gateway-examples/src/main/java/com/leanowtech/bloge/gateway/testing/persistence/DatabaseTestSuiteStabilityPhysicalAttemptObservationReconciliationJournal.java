package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-authoritative physical-attempt observation reconciliation journal.
 *
 * <p>The immutable start journal is the target source, so a crash after provider start but before a
 * start receipt cannot make the attempt undiscoverable. Missing target projections are materialized
 * in bounded pages. A separate scope row rotates successful claims across tenant/environment
 * scopes; target order inside a scope is database-clock eligibility followed by immutable attempt
 * identity.</p>
 *
 * <p>Claims and completions are short local transactions. Provider calls never execute while a
 * database transaction is open. Exact completion replay is retained only until a successor lease
 * replaces the fence. Expired leases are eligible for takeover, while a verified observation
 * committed before lease loss remains discoverable through the independent observation journal.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
        implements TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal {

    private static final String TARGET_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationReconciliationTarget.v1";
    private static final String SCOPE_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationReconciliationScope.v1";
    private static final String LEASE_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationReconciliationLease.v1";
    private static final String RESULT_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptObservationReconciliationResult.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Instant EMPTY_TIME = Instant.EPOCH;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityPhysicalAttemptStartJournal starts;
    private final Policy policy;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates a reconciliation journal using the JDBC datasource transaction manager.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param starts integrity-verifying start journal over the same datasource
     * @param policy bounded claim, retry, uncertainty, and horizon policy
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            Policy policy) {
        this(jdbc, objectMapper, starts, policy, localTransactionManager(jdbc));
    }

    /**
     * Creates a reconciliation journal with an explicit same-datasource transaction manager.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param starts integrity-verifying start journal over the same datasource
     * @param policy bounded claim, retry, uncertainty, and horizon policy
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            Policy policy,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.starts = Objects.requireNonNull(starts, "starts");
        this.policy = Objects.requireNonNull(policy, "policy");
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** Creates reconciliation target, fair-scope, and due-work indexes. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_stability_attempt_observation_reconciliation_scopes (
                    scope_fingerprint VARCHAR(71) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    last_claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    claim_sequence BIGINT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_stability_attempt_observation_reconciliation_scope
                        UNIQUE (tenant_id, environment_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_stability_attempt_observation_reconciliation_targets (
                    attempt_id VARCHAR(96) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    scope_fingerprint VARCHAR(71) NOT NULL,
                    start_command_id VARCHAR(128) NOT NULL,
                    start_command_fingerprint VARCHAR(71) NOT NULL,
                    start_command_json CLOB NOT NULL,
                    provider_id VARCHAR(255) NOT NULL,
                    deployment_id VARCHAR(255) NOT NULL,
                    target_status VARCHAR(32) NOT NULL,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(36) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    automatic_attempts BIGINT NOT NULL,
                    consecutive_uncertainty INTEGER NOT NULL,
                    consecutive_local_failures INTEGER NOT NULL,
                    last_observation_command_id VARCHAR(128) NOT NULL,
                    last_outcome VARCHAR(32) NOT NULL,
                    last_result_fingerprint VARCHAR(71) NOT NULL,
                    first_prepared_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_stability_attempt_observation_reconciliation_start
                        UNIQUE (start_command_id)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_stability_attempt_observation_reconciliation_targets
                ADD COLUMN IF NOT EXISTS consecutive_local_failures INTEGER DEFAULT 0 NOT NULL
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_stability_attempt_observation_reconciliation_due
                ON rg_test_stability_attempt_observation_reconciliation_targets (
                    scope_fingerprint, target_status, next_attempt_at, lease_until, attempt_id
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Policy policy() {
        return policy;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Claim> claimNext(String ownerId) {
        String owner = requireIdentifier(ownerId, "ownerId");
        discoverTargets();
        Optional<Claim> result = mutations.execute(status -> claimDue(owner));
        return result == null ? Optional.empty() : result;
    }

    /** {@inheritDoc} */
    @Override
    public Completion complete(Lease lease, Result result) {
        Lease requiredLease = requireLease(lease);
        Result requiredResult = Objects.requireNonNull(result, "result");
        Completion completion = mutations.execute(status -> {
            StoredTarget current = lockTarget(requiredLease.attemptId());
            if (current == null) {
                throw conflict(ConflictException.Reason.LEASE_LOST);
            }
            validateTarget(current);
            String resultFingerprint = resultFingerprint(requiredLease, requiredResult);
            if (!current.leaseMatches(requiredLease)) {
                if (current.leaseEpoch() == requiredLease.epoch()
                        && current.lastResultFingerprint().equals(resultFingerprint)) {
                    return replay(current);
                }
                throw conflict(ConflictException.Reason.LEASE_LOST);
            }
            Instant now = databaseNow();
            if (current.targetStatus() != TargetStatus.LEASED
                    || !now.isBefore(current.leaseUntil())) {
                if (current.lastResultFingerprint().equals(resultFingerprint)) {
                    return replay(current);
                }
                throw conflict(current.targetStatus() == TargetStatus.LEASED
                        ? ConflictException.Reason.LEASE_LOST
                        : ConflictException.Reason.RESULT_CONFLICT);
            }

            StoredTarget successor = completed(current, requiredResult,
                    resultFingerprint, now);
            updateTarget(current, successor);
            return completion(successor, completionStatus(successor));
        });
        return Objects.requireNonNull(completion, "reconciliation completion");
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Snapshot result = reads.execute(status -> {
            Instant now = databaseNow();
            Map<String, Long> counts = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT target_status, COUNT(*) AS item_count
                    FROM rg_test_stability_attempt_observation_reconciliation_targets
                    GROUP BY target_status
                    """, (RowCallbackHandler) rs -> counts.put(
                    rs.getString("target_status"), rs.getLong("item_count")));
            long due = number("""
                    SELECT COUNT(*)
                    FROM rg_test_stability_attempt_observation_reconciliation_targets
                    WHERE target_status = ? AND next_attempt_at <= ?
                    """, TargetStatus.READY.name(), Timestamp.from(now));
            long expired = number("""
                    SELECT COUNT(*)
                    FROM rg_test_stability_attempt_observation_reconciliation_targets
                    WHERE target_status = ? AND lease_until <= ?
                    """, TargetStatus.LEASED.name(), Timestamp.from(now));
            long undiscovered = number("""
                    SELECT COUNT(*)
                    FROM rg_test_stability_attempt_start_entries start
                    LEFT JOIN rg_test_stability_attempt_observation_reconciliation_targets target
                      ON target.start_command_id = start.command_id
                    WHERE target.start_command_id IS NULL
                    """);
            Instant oldest = jdbc.query("""
                    SELECT MIN(due_at) FROM (
                        SELECT next_attempt_at AS due_at
                        FROM rg_test_stability_attempt_observation_reconciliation_targets
                        WHERE target_status = ? AND next_attempt_at <= ?
                        UNION ALL
                        SELECT lease_until AS due_at
                        FROM rg_test_stability_attempt_observation_reconciliation_targets
                        WHERE target_status = ? AND lease_until <= ?
                    ) due_work
                    """, rs -> rs.next() && rs.getTimestamp(1) != null
                            ? rs.getTimestamp(1).toInstant() : null,
                    TargetStatus.READY.name(), Timestamp.from(now),
                    TargetStatus.LEASED.name(), Timestamp.from(now));
            for (String statusName : counts.keySet()) {
                try {
                    TargetStatus.valueOf(statusName);
                } catch (IllegalArgumentException invalid) {
                    throw integrity();
                }
            }
            return new Snapshot(now,
                    counts.getOrDefault(TargetStatus.READY.name(), 0L),
                    counts.getOrDefault(TargetStatus.LEASED.name(), 0L),
                    counts.getOrDefault(TargetStatus.TERMINAL.name(), 0L),
                    counts.getOrDefault(TargetStatus.QUARANTINED.name(), 0L),
                    due, expired, undiscovered, Optional.ofNullable(oldest));
        });
        return Objects.requireNonNull(result, "reconciliation snapshot");
    }

    private void discoverTargets() {
        List<SourceReference> missing = jdbc.query("""
                SELECT start.tenant_id, start.environment_id, start.command_id
                FROM rg_test_stability_attempt_start_entries start
                LEFT JOIN rg_test_stability_attempt_observation_reconciliation_targets target
                  ON target.start_command_id = start.command_id
                WHERE target.start_command_id IS NULL
                ORDER BY start.prepared_at, start.command_id
                FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> new SourceReference(
                rs.getString("tenant_id"), rs.getString("environment_id"),
                rs.getString("command_id")), policy.discoveryPageSize());
        for (SourceReference source : missing) {
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry entry = starts.find(
                    source.tenantId(), source.environmentId(), source.commandId())
                    .orElseThrow(DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                            ::integrity);
            Boolean inserted = mutations.execute(status -> {
                ensureScope(entry.command());
                ensureTarget(entry);
                return Boolean.TRUE;
            });
            if (!Boolean.TRUE.equals(inserted)) {
                throw new IllegalStateException(
                        "Physical-attempt reconciliation discovery returned no result");
            }
        }
    }

    private Optional<Claim> claimDue(String owner) {
        for (int inspected = 0; inspected < policy.discoveryPageSize(); inspected++) {
            Instant now = databaseNow();
            StoredScope candidateScope = eligibleScope(now);
            if (candidateScope == null) {
                return Optional.empty();
            }
            StoredScope scope = lockScope(candidateScope.scopeFingerprint());
            validateScope(scope);
            StoredTarget target = firstDue(scope.scopeFingerprint(), now);
            if (target == null) {
                continue;
            }
            validateTarget(target);
            Instant horizon = safePlus(target.firstPreparedAt(), policy.maximumHorizon());
            if (!now.isBefore(horizon)) {
                updateTarget(target, quarantineForHorizon(target, now));
                updateScope(scope, fingerprinted(scope.claimed(now)));
                continue;
            }

            long epoch = increment(target.leaseEpoch(), "reconciliation lease epoch");
            String token = UUID.randomUUID().toString();
            Instant leaseUntil = safePlus(now, policy.leaseDuration());
            String fence = leaseFingerprint(
                    target.attemptId(), owner, token, epoch, now, leaseUntil);
            StoredTarget leased = leased(
                    target, owner, token, epoch, now, leaseUntil);
            updateTarget(target, leased);
            updateScope(scope, fingerprinted(scope.claimed(now)));
            return Optional.of(new Claim(
                    new Lease(target.attemptId(), owner, token, epoch, now, leaseUntil, fence),
                    decode(target.startCommandJson()), target.automaticAttempts(),
                    target.consecutiveUncertainty(), target.firstPreparedAt()));
        }
        return Optional.empty();
    }

    private StoredTarget completed(
            StoredTarget current,
            Result result,
            String resultFingerprint,
            Instant now) {
        boolean providerFacing = switch (result.kind()) {
            case POSITIVE_ACTIVE, POSITIVE_TERMINAL, NON_CONFIRMING,
                    REMOTE_UNCERTAIN -> true;
            case RETAINED_TERMINAL, LOCAL_BACKPRESSURE -> false;
            case PERMANENT_FAILURE -> !result.observationCommandId().isEmpty();
        };
        long attempts = providerFacing
                ? increment(current.automaticAttempts(), "automatic reconciliation attempts")
                : current.automaticAttempts();
        int uncertainty = switch (result.kind()) {
            case POSITIVE_ACTIVE, POSITIVE_TERMINAL, RETAINED_TERMINAL -> 0;
            case NON_CONFIRMING, REMOTE_UNCERTAIN -> incrementInt(
                    current.consecutiveUncertainty(), "reconciliation uncertainty");
            case LOCAL_BACKPRESSURE, PERMANENT_FAILURE ->
                    current.consecutiveUncertainty();
        };
        int localFailures = result.kind() == ResultKind.LOCAL_BACKPRESSURE
                ? incrementInt(current.consecutiveLocalFailures(),
                "reconciliation local failures") : 0;
        Instant horizon = safePlus(current.firstPreparedAt(), policy.maximumHorizon());
        TargetStatus status;
        Instant next = EMPTY_TIME;
        if (result.kind() == ResultKind.POSITIVE_TERMINAL
                || result.kind() == ResultKind.RETAINED_TERMINAL) {
            status = TargetStatus.TERMINAL;
        } else if (result.kind() == ResultKind.PERMANENT_FAILURE
                || uncertainty >= policy.maximumConsecutiveUncertainty()
                || !now.isBefore(horizon)) {
            status = TargetStatus.QUARANTINED;
        } else {
            Duration delay = switch (result.kind()) {
                case POSITIVE_ACTIVE -> policy.activePollDelay();
                case NON_CONFIRMING, REMOTE_UNCERTAIN -> retryDelay(uncertainty);
                case LOCAL_BACKPRESSURE -> retryDelay(localFailures);
                case POSITIVE_TERMINAL, RETAINED_TERMINAL, PERMANENT_FAILURE ->
                        throw new IllegalStateException(
                        "Terminal reconciliation result reached retry scheduling");
            };
            next = safePlus(now, delay);
            if (!next.isBefore(horizon)) {
                status = TargetStatus.QUARANTINED;
                next = EMPTY_TIME;
            } else {
                status = TargetStatus.READY;
            }
        }
        String lastCommand = result.observationCommandId().isEmpty()
                ? current.lastObservationCommandId() : result.observationCommandId();
        return fingerprinted(new StoredTarget(
                current.attemptId(), current.tenantId(), current.environmentId(),
                current.scopeFingerprint(), current.startCommandId(),
                current.startCommandFingerprint(), current.startCommandJson(),
                current.providerId(), current.deploymentId(), status, next,
                current.leaseOwner(), current.leaseToken(), current.leaseEpoch(),
                current.leaseClaimedAt(), current.leaseUntil(), attempts, uncertainty,
                localFailures,
                lastCommand, result.kind().name(), resultFingerprint,
                current.firstPreparedAt(), now, ""));
    }

    private StoredTarget leased(
            StoredTarget target,
            String owner,
            String token,
            long epoch,
            Instant claimedAt,
            Instant leaseUntil) {
        return fingerprinted(new StoredTarget(
                target.attemptId(), target.tenantId(), target.environmentId(),
                target.scopeFingerprint(), target.startCommandId(),
                target.startCommandFingerprint(), target.startCommandJson(),
                target.providerId(), target.deploymentId(), TargetStatus.LEASED, EMPTY_TIME,
                owner, token, epoch, claimedAt, leaseUntil, target.automaticAttempts(),
                target.consecutiveUncertainty(), target.consecutiveLocalFailures(),
                target.lastObservationCommandId(),
                target.lastOutcome(), target.lastResultFingerprint(),
                target.firstPreparedAt(), claimedAt, ""));
    }

    private StoredTarget quarantineForHorizon(StoredTarget target, Instant now) {
        String resultFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", RESULT_SCHEMA,
                "attemptId", target.attemptId(),
                "kind", ResultKind.PERMANENT_FAILURE,
                "reason", "HORIZON_EXHAUSTED",
                "completedAt", now));
        return fingerprinted(new StoredTarget(
                target.attemptId(), target.tenantId(), target.environmentId(),
                target.scopeFingerprint(), target.startCommandId(),
                target.startCommandFingerprint(), target.startCommandJson(),
                target.providerId(), target.deploymentId(), TargetStatus.QUARANTINED,
                EMPTY_TIME, target.leaseOwner(), target.leaseToken(), target.leaseEpoch(),
                target.leaseClaimedAt(), target.leaseUntil(), target.automaticAttempts(),
                target.consecutiveUncertainty(), target.consecutiveLocalFailures(),
                target.lastObservationCommandId(),
                ResultKind.PERMANENT_FAILURE.name(), resultFingerprint,
                target.firstPreparedAt(), now, ""));
    }

    private Duration retryDelay(int uncertainty) {
        Duration delay = policy.initialRetryDelay();
        for (int index = 1; index < uncertainty; index++) {
            if (delay.compareTo(policy.maximumRetryDelay().dividedBy(2)) > 0) {
                return policy.maximumRetryDelay();
            }
            delay = delay.multipliedBy(2);
            if (delay.compareTo(policy.maximumRetryDelay()) >= 0) {
                return policy.maximumRetryDelay();
            }
        }
        return delay;
    }

    private Completion replay(StoredTarget target) {
        completionStatus(target);
        return completion(target, CompletionStatus.REPLAYED);
    }

    private static CompletionStatus completionStatus(StoredTarget target) {
        return switch (target.targetStatus()) {
            case READY -> CompletionStatus.RESCHEDULED;
            case TERMINAL -> CompletionStatus.TERMINAL;
            case QUARANTINED -> CompletionStatus.QUARANTINED;
            case LEASED -> throw integrity();
        };
    }

    private static Completion completion(
            StoredTarget target, CompletionStatus status) {
        return new Completion(status, target.targetStatus(), target.automaticAttempts(),
                target.consecutiveUncertainty(),
                target.targetStatus() == TargetStatus.READY
                        ? Optional.of(target.nextAttemptAt()) : Optional.empty(),
                target.updatedAt());
    }

    private void ensureScope(TestSuiteStabilityPhysicalAttemptStartCommand command) {
        String scopeFingerprint = scopeFingerprint(command.identity().tenantId(),
                command.identity().environmentId());
        StoredScope existing = scope(scopeFingerprint);
        if (existing != null) {
            validateScope(existing);
            if (!existing.tenantId().equals(command.identity().tenantId())
                    || !existing.environmentId().equals(
                    command.identity().environmentId())) {
                throw integrity();
            }
            return;
        }
        StoredScope initial = fingerprinted(new StoredScope(
                scopeFingerprint, command.identity().tenantId(),
                command.identity().environmentId(), EMPTY_TIME, 0, EMPTY_TIME, ""));
        try {
            jdbc.update("""
                    INSERT INTO rg_test_stability_attempt_observation_reconciliation_scopes (
                        scope_fingerprint, tenant_id, environment_id, last_claimed_at,
                        claim_sequence, updated_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, initial.scopeFingerprint(), initial.tenantId(),
                    initial.environmentId(), Timestamp.from(initial.lastClaimedAt()),
                    initial.claimSequence(), Timestamp.from(initial.updatedAt()),
                    initial.recordFingerprint());
        } catch (DuplicateKeyException concurrent) {
            validateScope(Objects.requireNonNull(scope(scopeFingerprint)));
        }
    }

    private void ensureTarget(TestSuiteStabilityPhysicalAttemptStartJournal.Entry start) {
        TestSuiteStabilityPhysicalAttemptStartCommand command = start.command();
        StoredTarget existing = target(command.identity().attemptId());
        if (existing != null) {
            validateTarget(existing);
            if (!decode(existing.startCommandJson()).equals(command)) {
                throw integrity();
            }
            return;
        }
        Instant preparedAt = start.preparedAt();
        String scope = scopeFingerprint(
                command.identity().tenantId(), command.identity().environmentId());
        StoredTarget initial = fingerprinted(new StoredTarget(
                command.identity().attemptId(), command.identity().tenantId(),
                command.identity().environmentId(), scope, command.commandId(),
                command.commandFingerprint(), encode(command), command.identity().providerId(),
                command.identity().deploymentId(), TargetStatus.READY, preparedAt,
                "", "", 0, EMPTY_TIME, EMPTY_TIME, 0, 0, 0, "", "NONE", "",
                preparedAt, preparedAt, ""));
        try {
            insertTarget(initial);
        } catch (DuplicateKeyException concurrent) {
            StoredTarget retained = target(command.identity().attemptId());
            if (retained == null || !decode(
                    validateTarget(retained).startCommandJson()).equals(command)) {
                throw integrity();
            }
        }
    }

    private StoredScope eligibleScope(Instant now) {
        List<StoredScope> rows = jdbc.query("""
                SELECT scope.scope_fingerprint, scope.tenant_id, scope.environment_id,
                       scope.last_claimed_at, scope.claim_sequence, scope.updated_at,
                       scope.record_fingerprint
                FROM rg_test_stability_attempt_observation_reconciliation_scopes scope
                WHERE EXISTS (
                    SELECT 1
                    FROM rg_test_stability_attempt_observation_reconciliation_targets target
                    WHERE target.scope_fingerprint = scope.scope_fingerprint
                      AND ((target.target_status = ? AND target.next_attempt_at <= ?)
                        OR (target.target_status = ? AND target.lease_until <= ?))
                )
                ORDER BY scope.last_claimed_at, scope.scope_fingerprint
                FETCH FIRST 1 ROW ONLY
                """, this::mapScope, TargetStatus.READY.name(), Timestamp.from(now),
                TargetStatus.LEASED.name(), Timestamp.from(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredTarget firstDue(String scopeFingerprint, Instant now) {
        List<StoredTarget> rows = jdbc.query(targetSelect() + """
                 WHERE scope_fingerprint = ?
                   AND ((target_status = ? AND next_attempt_at <= ?)
                     OR (target_status = ? AND lease_until <= ?))
                 ORDER BY CASE WHEN target_status = ? THEN lease_until ELSE next_attempt_at END,
                          attempt_id
                 FETCH FIRST 1 ROW ONLY
                 FOR UPDATE
                """, this::mapTarget, scopeFingerprint, TargetStatus.READY.name(),
                Timestamp.from(now), TargetStatus.LEASED.name(), Timestamp.from(now),
                TargetStatus.LEASED.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredScope lockScope(String scopeFingerprint) {
        List<StoredScope> rows = jdbc.query("""
                SELECT scope_fingerprint, tenant_id, environment_id, last_claimed_at,
                       claim_sequence, updated_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_reconciliation_scopes
                WHERE scope_fingerprint = ? FOR UPDATE
                """, this::mapScope, scopeFingerprint);
        if (rows.size() != 1) {
            throw integrity();
        }
        return rows.getFirst();
    }

    private StoredTarget lockTarget(String attemptId) {
        List<StoredTarget> rows = jdbc.query(targetSelect()
                + " WHERE attempt_id = ? FOR UPDATE", this::mapTarget, attemptId);
        if (rows.size() > 1) {
            throw integrity();
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredScope scope(String scopeFingerprint) {
        List<StoredScope> rows = jdbc.query("""
                SELECT scope_fingerprint, tenant_id, environment_id, last_claimed_at,
                       claim_sequence, updated_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_reconciliation_scopes
                WHERE scope_fingerprint = ?
                """, this::mapScope, scopeFingerprint);
        if (rows.size() > 1) {
            throw integrity();
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredTarget target(String attemptId) {
        List<StoredTarget> rows = jdbc.query(targetSelect() + " WHERE attempt_id = ?",
                this::mapTarget, attemptId);
        if (rows.size() > 1) {
            throw integrity();
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertTarget(StoredTarget value) {
        jdbc.update("""
                INSERT INTO rg_test_stability_attempt_observation_reconciliation_targets (
                    attempt_id, tenant_id, environment_id, scope_fingerprint,
                    start_command_id, start_command_fingerprint, start_command_json,
                    provider_id, deployment_id, target_status, next_attempt_at,
                    lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                    automatic_attempts, consecutive_uncertainty,
                    consecutive_local_failures,
                    last_observation_command_id, last_outcome, last_result_fingerprint,
                    first_prepared_at, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.attemptId(), value.tenantId(), value.environmentId(),
                value.scopeFingerprint(), value.startCommandId(),
                value.startCommandFingerprint(), value.startCommandJson(),
                value.providerId(), value.deploymentId(), value.targetStatus().name(),
                Timestamp.from(value.nextAttemptAt()), value.leaseOwner(), value.leaseToken(),
                value.leaseEpoch(), Timestamp.from(value.leaseClaimedAt()),
                Timestamp.from(value.leaseUntil()), value.automaticAttempts(),
                value.consecutiveUncertainty(), value.consecutiveLocalFailures(),
                value.lastObservationCommandId(),
                value.lastOutcome(), value.lastResultFingerprint(),
                Timestamp.from(value.firstPreparedAt()), Timestamp.from(value.updatedAt()),
                value.recordFingerprint());
    }

    private void updateTarget(StoredTarget previous, StoredTarget value) {
        int updated = jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_reconciliation_targets
                SET target_status = ?, next_attempt_at = ?, lease_owner = ?,
                    lease_token = ?, lease_epoch = ?, lease_claimed_at = ?, lease_until = ?,
                    automatic_attempts = ?, consecutive_uncertainty = ?,
                    consecutive_local_failures = ?,
                    last_observation_command_id = ?, last_outcome = ?,
                    last_result_fingerprint = ?, updated_at = ?, record_fingerprint = ?
                WHERE attempt_id = ? AND record_fingerprint = ?
                """, value.targetStatus().name(), Timestamp.from(value.nextAttemptAt()),
                value.leaseOwner(), value.leaseToken(), value.leaseEpoch(),
                Timestamp.from(value.leaseClaimedAt()), Timestamp.from(value.leaseUntil()),
                value.automaticAttempts(), value.consecutiveUncertainty(),
                value.consecutiveLocalFailures(),
                value.lastObservationCommandId(), value.lastOutcome(),
                value.lastResultFingerprint(), Timestamp.from(value.updatedAt()),
                value.recordFingerprint(), previous.attemptId(), previous.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Physical-attempt reconciliation target concurrent update failed");
        }
    }

    private void updateScope(StoredScope previous, StoredScope value) {
        int updated = jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_reconciliation_scopes
                SET last_claimed_at = ?, claim_sequence = ?, updated_at = ?,
                    record_fingerprint = ?
                WHERE scope_fingerprint = ? AND record_fingerprint = ?
                """, Timestamp.from(value.lastClaimedAt()), value.claimSequence(),
                Timestamp.from(value.updatedAt()), value.recordFingerprint(),
                previous.scopeFingerprint(), previous.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Physical-attempt reconciliation scope concurrent update failed");
        }
    }

    private StoredTarget validateTarget(StoredTarget value) {
        try {
            TestSuiteStabilityPhysicalAttemptStartCommand command = decode(
                    value.startCommandJson());
            String commandFingerprint = ProtocolFingerprint.of(
                    objectMapper, command.canonicalMaterial());
            String expectedScope = scopeFingerprint(value.tenantId(), value.environmentId());
            String expectedRecord = targetFingerprint(value.withFingerprint(""));
            boolean leased = value.targetStatus() == TargetStatus.LEASED;
            boolean completed = !value.lastResultFingerprint().isEmpty();
            if (!value.attemptId().equals(command.identity().attemptId())
                    || !value.tenantId().equals(command.identity().tenantId())
                    || !value.environmentId().equals(command.identity().environmentId())
                    || !value.scopeFingerprint().equals(expectedScope)
                    || !value.startCommandId().equals(command.commandId())
                    || !value.startCommandFingerprint().equals(command.commandFingerprint())
                    || !value.startCommandFingerprint().equals(commandFingerprint)
                    || !value.providerId().equals(command.identity().providerId())
                    || !value.deploymentId().equals(command.identity().deploymentId())
                    || !value.recordFingerprint().equals(expectedRecord)
                    || value.leaseEpoch() < 0 || value.automaticAttempts() < 0
                    || value.consecutiveUncertainty() < 0
                    || value.consecutiveLocalFailures() < 0
                    || value.updatedAt().isBefore(value.firstPreparedAt())
                    || leased && (value.leaseOwner().isEmpty()
                    || value.leaseToken().isEmpty()
                    || value.leaseEpoch() < 1
                    || !value.leaseUntil().isAfter(value.leaseClaimedAt()))
                    || (value.targetStatus() == TargetStatus.READY)
                    != value.nextAttemptAt().isAfter(EMPTY_TIME)
                    || completed != !value.lastOutcome().equals("NONE")
                    || completed != (value.lastResultFingerprint()
                    .matches("sha256:[a-f0-9]{64}"))) {
                throw integrity();
            }
            if (!value.lastObservationCommandId().isEmpty()
                    && !value.lastObservationCommandId().matches(
                    "stability-attempt-observe-[a-f0-9]{64}")) {
                throw integrity();
            }
            if (!value.lastOutcome().equals("NONE")) {
                ResultKind.valueOf(value.lastOutcome());
            }
            if (!value.leaseToken().isEmpty()) {
                UUID.fromString(value.leaseToken());
            }
            return value;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ConflictException conflict) {
                throw conflict;
            }
            throw integrity();
        }
    }

    private StoredScope validateScope(StoredScope value) {
        String expectedScope = scopeFingerprint(value.tenantId(), value.environmentId());
        String expectedRecord = scopeRecordFingerprint(value.withFingerprint(""));
        if (!value.scopeFingerprint().equals(expectedScope)
                || !value.recordFingerprint().equals(expectedRecord)
                || value.claimSequence() < 0 || value.updatedAt().isBefore(EMPTY_TIME)
                || value.lastClaimedAt().isAfter(value.updatedAt())) {
            throw integrity();
        }
        return value;
    }

    private Lease requireLease(Lease lease) {
        Lease required = Objects.requireNonNull(lease, "lease");
        String expected = leaseFingerprint(
                required.attemptId(), required.ownerId(), required.token(), required.epoch(),
                required.claimedAt(), required.leaseUntil());
        if (!required.fenceFingerprint().equals(expected)) {
            throw conflict(ConflictException.Reason.INTEGRITY_FAILURE);
        }
        return required;
    }

    private StoredTarget fingerprinted(StoredTarget value) {
        return value.withFingerprint(targetFingerprint(value));
    }

    private StoredScope fingerprinted(StoredScope value) {
        return value.withFingerprint(scopeRecordFingerprint(value));
    }

    private String targetFingerprint(StoredTarget value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", TARGET_SCHEMA);
        material.put("attemptId", value.attemptId());
        material.put("tenantId", value.tenantId());
        material.put("environmentId", value.environmentId());
        material.put("scopeFingerprint", value.scopeFingerprint());
        material.put("startCommandFingerprint", value.startCommandFingerprint());
        material.put("providerId", value.providerId());
        material.put("deploymentId", value.deploymentId());
        material.put("targetStatus", value.targetStatus());
        material.put("nextAttemptAt", value.nextAttemptAt());
        material.put("leaseOwner", value.leaseOwner());
        material.put("leaseToken", value.leaseToken());
        material.put("leaseEpoch", value.leaseEpoch());
        material.put("leaseClaimedAt", value.leaseClaimedAt());
        material.put("leaseUntil", value.leaseUntil());
        material.put("automaticAttempts", value.automaticAttempts());
        material.put("consecutiveUncertainty", value.consecutiveUncertainty());
        material.put("consecutiveLocalFailures", value.consecutiveLocalFailures());
        material.put("lastObservationCommandId", value.lastObservationCommandId());
        material.put("lastOutcome", value.lastOutcome());
        material.put("lastResultFingerprint", value.lastResultFingerprint());
        material.put("firstPreparedAt", value.firstPreparedAt());
        material.put("updatedAt", value.updatedAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String scopeRecordFingerprint(StoredScope value) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", SCOPE_SCHEMA,
                "scopeFingerprint", value.scopeFingerprint(),
                "tenantId", value.tenantId(),
                "environmentId", value.environmentId(),
                "lastClaimedAt", value.lastClaimedAt(),
                "claimSequence", value.claimSequence(),
                "updatedAt", value.updatedAt()));
    }

    private String scopeFingerprint(String tenantId, String environmentId) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", SCOPE_SCHEMA,
                "tenantId", tenantId,
                "environmentId", environmentId));
    }

    private String leaseFingerprint(
            String attemptId,
            String owner,
            String token,
            long epoch,
            Instant claimedAt,
            Instant leaseUntil) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", LEASE_SCHEMA,
                "attemptId", attemptId,
                "ownerId", owner,
                "token", token,
                "epoch", epoch,
                "claimedAt", claimedAt,
                "leaseUntil", leaseUntil));
    }

    private String resultFingerprint(Lease lease, Result result) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", RESULT_SCHEMA,
                "leaseFenceFingerprint", lease.fenceFingerprint(),
                "kind", result.kind(),
                "observationCommandId", result.observationCommandId()));
    }

    private StoredTarget mapTarget(ResultSet rs, int row) throws SQLException {
        try {
            return new StoredTarget(
                    rs.getString("attempt_id"), rs.getString("tenant_id"),
                    rs.getString("environment_id"), rs.getString("scope_fingerprint"),
                    rs.getString("start_command_id"),
                    rs.getString("start_command_fingerprint"),
                    rs.getString("start_command_json"), rs.getString("provider_id"),
                    rs.getString("deployment_id"),
                    TargetStatus.valueOf(rs.getString("target_status")),
                    rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getString("lease_owner"), rs.getString("lease_token"),
                    rs.getLong("lease_epoch"),
                    rs.getTimestamp("lease_claimed_at").toInstant(),
                    rs.getTimestamp("lease_until").toInstant(),
                    rs.getLong("automatic_attempts"),
                    rs.getInt("consecutive_uncertainty"),
                    rs.getInt("consecutive_local_failures"),
                    rs.getString("last_observation_command_id"),
                    rs.getString("last_outcome"),
                    rs.getString("last_result_fingerprint"),
                    rs.getTimestamp("first_prepared_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("record_fingerprint"));
        } catch (RuntimeException invalid) {
            throw new SQLException("Invalid physical-attempt reconciliation target", invalid);
        }
    }

    private StoredScope mapScope(ResultSet rs, int row) throws SQLException {
        return new StoredScope(
                rs.getString("scope_fingerprint"), rs.getString("tenant_id"),
                rs.getString("environment_id"),
                rs.getTimestamp("last_claimed_at").toInstant(),
                rs.getLong("claim_sequence"), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private static String targetSelect() {
        return """
                SELECT attempt_id, tenant_id, environment_id, scope_fingerprint,
                       start_command_id, start_command_fingerprint, start_command_json,
                       provider_id, deployment_id, target_status, next_attempt_at,
                       lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                       automatic_attempts, consecutive_uncertainty,
                       consecutive_local_failures,
                       last_observation_command_id, last_outcome, last_result_fingerprint,
                       first_prepared_at, updated_at, record_fingerprint
                FROM rg_test_stability_attempt_observation_reconciliation_targets
                """;
    }

    private long number(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        if (value == null || value < 0) {
            throw integrity();
        }
        return value;
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no value");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException(
                    "Physical-attempt reconciliation source serialization failed", invalid);
        }
    }

    private TestSuiteStabilityPhysicalAttemptStartCommand decode(String value) {
        try {
            return objectMapper.readValue(
                    value, TestSuiteStabilityPhysicalAttemptStartCommand.class);
        } catch (JsonProcessingException invalid) {
            throw integrity();
        }
    }

    private static String requireIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid reconciliation " + field);
        }
        return normalized;
    }

    private static Instant safePlus(Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (RuntimeException overflow) {
            throw new IllegalStateException("Physical-attempt reconciliation time overflow");
        }
    }

    private static long increment(long value, String field) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(field + " overflow");
        }
    }

    private static int incrementInt(int value, String field) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(field + " overflow");
        }
    }

    private static ConflictException conflict(ConflictException.Reason reason) {
        return new ConflictException(reason);
    }

    private static ConflictException integrity() {
        return conflict(ConflictException.Reason.INTEGRITY_FAILURE);
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        javax.sql.DataSource dataSource = Objects.requireNonNull(
                Objects.requireNonNull(jdbc, "jdbc").getDataSource(), "jdbc.dataSource");
        return new DataSourceTransactionManager(dataSource);
    }

    private record SourceReference(String tenantId, String environmentId, String commandId) {
    }

    private record StoredScope(
            String scopeFingerprint,
            String tenantId,
            String environmentId,
            Instant lastClaimedAt,
            long claimSequence,
            Instant updatedAt,
            String recordFingerprint) {

        private StoredScope claimed(Instant now) {
            return new StoredScope(scopeFingerprint, tenantId, environmentId, now,
                    increment(claimSequence, "reconciliation scope sequence"), now, "");
        }

        private StoredScope withFingerprint(String fingerprint) {
            return new StoredScope(scopeFingerprint, tenantId, environmentId, lastClaimedAt,
                    claimSequence, updatedAt, fingerprint);
        }
    }

    private record StoredTarget(
            String attemptId,
            String tenantId,
            String environmentId,
            String scopeFingerprint,
            String startCommandId,
            String startCommandFingerprint,
            String startCommandJson,
            String providerId,
            String deploymentId,
            TargetStatus targetStatus,
            Instant nextAttemptAt,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseClaimedAt,
            Instant leaseUntil,
            long automaticAttempts,
            int consecutiveUncertainty,
            int consecutiveLocalFailures,
            String lastObservationCommandId,
            String lastOutcome,
            String lastResultFingerprint,
            Instant firstPreparedAt,
            Instant updatedAt,
            String recordFingerprint) {

        private StoredTarget withFingerprint(String fingerprint) {
            return new StoredTarget(
                    attemptId, tenantId, environmentId, scopeFingerprint,
                    startCommandId, startCommandFingerprint, startCommandJson,
                    providerId, deploymentId, targetStatus, nextAttemptAt,
                    leaseOwner, leaseToken, leaseEpoch, leaseClaimedAt, leaseUntil,
                    automaticAttempts, consecutiveUncertainty, consecutiveLocalFailures,
                    lastObservationCommandId, lastOutcome, lastResultFingerprint,
                    firstPreparedAt, updatedAt, fingerprint);
        }

        private boolean leaseMatches(Lease lease) {
            return attemptId.equals(lease.attemptId())
                    && leaseOwner.equals(lease.ownerId())
                    && leaseToken.equals(lease.token())
                    && leaseEpoch == lease.epoch()
                    && leaseClaimedAt.equals(lease.claimedAt())
                    && leaseUntil.equals(lease.leaseUntil());
        }
    }

}
