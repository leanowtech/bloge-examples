package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal;
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

/**
 * Database adapter for transaction-bound physical terminal projection-work registration.
 *
 * <p>The adapter returns a mutation instead of opening its own transaction. The observation
 * reconciliation journal applies that mutation through its already enlisted JDBC facade so the
 * terminal target transition and work registration commit or roll back together. Reads recompute
 * both trigger identity and whole-row integrity before returning a scoped entry. Claims,
 * takeovers, and completions use short database-clock transactions; coordinator and provider I/O
 * always happen outside those transactions.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
        implements TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal {

    private static final String ENTRY_FINGERPRINT_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkRecord.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Policy policy;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates a work journal over the isolated test-runtime datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Policy.DEFAULT, localTransactionManager(jdbc));
    }

    /**
     * Creates a work journal with an explicit replica-shared policy.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param policy exact lease and retry policy
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
            JdbcTemplate jdbc, ObjectMapper objectMapper, Policy policy) {
        this(jdbc, objectMapper, policy, localTransactionManager(jdbc));
    }

    /**
     * Creates a work journal with an explicit same-datasource transaction manager.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param policy exact lease and retry policy
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Policy policy,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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

    /** Creates the forward-compatible work lifecycle table and due-work index. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_stability_attempt_terminal_projection_work (
                    attempt_id VARCHAR(96) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    work_id VARCHAR(128) NOT NULL,
                    trigger_fingerprint VARCHAR(71) NOT NULL,
                    trigger_json CLOB NOT NULL,
                    observation_command_id VARCHAR(128) NOT NULL,
                    reconciliation_result_fingerprint VARCHAR(71) NOT NULL,
                    work_status VARCHAR(32) NOT NULL,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(36) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    execution_attempts BIGINT NOT NULL,
                    consecutive_proof_pending INTEGER NOT NULL,
                    consecutive_unavailable INTEGER NOT NULL,
                    last_result_kind VARCHAR(32) NOT NULL,
                    last_failure_reason VARCHAR(64) NOT NULL,
                    projection_id VARCHAR(128) NOT NULL,
                    last_result_fingerprint VARCHAR(71) NOT NULL,
                    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_stability_attempt_terminal_projection_work_id
                        UNIQUE (work_id),
                    CONSTRAINT uq_rg_test_stability_attempt_terminal_projection_observation
                        UNIQUE (observation_command_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_stability_attempt_terminal_projection_work_due
                ON rg_test_stability_attempt_terminal_projection_work (
                    work_status, next_attempt_at, lease_until, attempt_id
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
    public TestRuntimeTransactionMutation boundRegister(Trigger trigger) {
        Trigger exact = requireTrigger(trigger);
        String triggerJson = encode(exact);
        return transactionJdbc -> register(
                Objects.requireNonNull(transactionJdbc, "transactionJdbc"),
                exact, triggerJson);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Claim> claimNext(String ownerId) {
        String owner = required(ownerId, "ownerId");
        if (!owner.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")) {
            throw new IllegalArgumentException("Invalid projection-work ownerId");
        }
        Optional<Claim> claimed = mutations.execute(status -> claimDue(owner));
        return claimed == null ? Optional.empty() : claimed;
    }

    /** {@inheritDoc} */
    @Override
    public Completion complete(Lease lease, Result result) {
        Lease exactLease = requireLease(lease);
        Result exactResult = Objects.requireNonNull(result, "result");
        Completion completion = mutations.execute(status -> completeLeased(
                exactLease, exactResult));
        return Objects.requireNonNull(completion, "projection-work completion");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(String tenantId, String environmentId, String attemptId) {
        String tenant = required(tenantId, "tenantId");
        String environment = required(environmentId, "environmentId");
        String attempt = required(attemptId, "attemptId");
        Optional<Entry> result = reads.execute(status -> scopedFind(
                tenant, environment, attempt));
        return result == null ? Optional.empty() : result;
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Snapshot result = reads.execute(status -> snapshotRows());
        return Objects.requireNonNull(result, "projection-work snapshot");
    }

    private Optional<Entry> scopedFind(
            String tenant, String environment, String attempt) {
        List<Entry> rows = jdbc.query("""
                SELECT attempt_id, tenant_id, environment_id, work_id,
                       trigger_fingerprint, trigger_json, observation_command_id,
                       reconciliation_result_fingerprint, work_status, next_attempt_at,
                       lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                       execution_attempts, consecutive_proof_pending,
                       consecutive_unavailable, last_result_kind, last_failure_reason,
                       projection_id, last_result_fingerprint, registered_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE tenant_id = ? AND environment_id = ? AND attempt_id = ?
                """, this::map, tenant, environment, attempt);
        if (rows.size() > 1) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return rows.stream().findFirst().map(this::validate);
    }

    private Optional<Claim> claimDue(String owner) {
        for (int inspected = 0; inspected < policy.claimInspectionLimit(); inspected++) {
            Instant now = databaseNow(jdbc);
            Entry current = firstDue(now);
            if (current == null) {
                return Optional.empty();
            }
            validate(current);
            if (!eligible(current, now)) {
                continue;
            }
            long epoch = increment(current.leaseEpoch(), "projection-work lease epoch");
            String token = UUID.randomUUID().toString();
            Instant leaseUntil = safePlus(now, policy.leaseDuration());
            Entry leased = fingerprinted(new Entry(
                    current.schemaVersion(), current.trigger(), Status.LEASED, Instant.EPOCH,
                    owner, token, epoch, now, leaseUntil, current.executionAttempts(),
                    current.consecutiveProofPending(), current.consecutiveUnavailable(),
                    current.lastResultKind(), current.lastFailureReason(),
                    current.projectionId(), current.lastResultFingerprint(),
                    current.registeredAt(), now, placeholderFingerprint()));
            update(current, leased);
            String fence = leaseFingerprint(leased);
            return Optional.of(new Claim(
                    new Lease(leased.trigger().workId(), leased.trigger().attemptId(), owner,
                            token, epoch, now, leaseUntil, fence), leased.trigger(),
                    leased.executionAttempts(), leased.consecutiveProofPending(),
                    leased.consecutiveUnavailable(), leased.registeredAt()));
        }
        return Optional.empty();
    }

    private Completion completeLeased(Lease lease, Result result) {
        Entry current = lock(lease.attemptId());
        if (current == null) {
            throw conflict(ConflictReason.LEASE_LOST);
        }
        validate(current);
        String resultFingerprint = resultFingerprint(lease, result);
        if (!leaseMatches(current, lease)) {
            return replayOrConflict(current, lease, result, resultFingerprint);
        }
        Instant now = databaseNow(jdbc);
        if (current.status() != Status.LEASED || !now.isBefore(current.leaseUntil())) {
            return replayOrConflict(current, lease, result, resultFingerprint);
        }
        long attempts = increment(current.executionAttempts(),
                "projection-work execution attempts");
        int proofPending = result.kind() == ResultKind.PROOF_PENDING
                ? incrementInt(current.consecutiveProofPending(),
                "projection-work proof-pending streak") : 0;
        int unavailable = result.kind() == ResultKind.UNAVAILABLE
                ? incrementInt(current.consecutiveUnavailable(),
                "projection-work unavailable streak") : 0;
        Status status;
        Instant next = Instant.EPOCH;
        String projectionId = "";
        if (result.kind() == ResultKind.PROJECTED
                || result.kind() == ResultKind.REPLAYED) {
            status = Status.COMPLETED;
            projectionId = result.projectionId();
        } else if (result.kind() == ResultKind.PERMANENT_CONFLICT) {
            status = Status.QUARANTINED;
        } else {
            status = Status.READY;
            Duration base = result.kind() == ResultKind.PROOF_PENDING
                    ? policy.initialProofPendingDelay() : policy.initialUnavailableDelay();
            int consecutive = result.kind() == ResultKind.PROOF_PENDING
                    ? proofPending : unavailable;
            next = safePlus(now, retryDelay(base, consecutive));
        }
        Entry successor = fingerprinted(new Entry(
                current.schemaVersion(), current.trigger(), status, next,
                "", "", current.leaseEpoch(), Instant.EPOCH, Instant.EPOCH,
                attempts, proofPending, unavailable, result.kind(), result.failureReason(),
                projectionId, resultFingerprint, current.registeredAt(), now,
                placeholderFingerprint()));
        update(current, successor);
        CompletionStatus completionStatus = switch (status) {
            case COMPLETED -> CompletionStatus.COMPLETED;
            case READY -> CompletionStatus.RESCHEDULED;
            case QUARANTINED -> CompletionStatus.QUARANTINED;
            case LEASED -> throw new IllegalStateException(
                    "Projection-work completion retained a lease");
        };
        return completion(successor, completionStatus, result);
    }

    private Completion replayOrConflict(
            Entry current,
            Lease lease,
            Result result,
            String resultFingerprint) {
        if (current.leaseEpoch() == lease.epoch()
                && current.lastResultFingerprint().equals(resultFingerprint)) {
            return completion(current, CompletionStatus.REPLAYED, result);
        }
        if (current.leaseEpoch() == lease.epoch()
                && !current.lastResultFingerprint().isEmpty()) {
            throw conflict(ConflictReason.RESULT_CONFLICT);
        }
        throw conflict(ConflictReason.LEASE_LOST);
    }

    private Completion completion(
            Entry entry, CompletionStatus status, Result result) {
        return new Completion(status, entry.status(), entry.executionAttempts(),
                entry.consecutiveProofPending(), entry.consecutiveUnavailable(),
                entry.status() == Status.READY
                        ? Optional.of(entry.nextAttemptAt()) : Optional.empty(),
                result, entry.updatedAt());
    }

    private Snapshot snapshotRows() {
        Instant now = databaseNow(jdbc);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT work_status, COUNT(*) AS item_count
                FROM rg_test_stability_attempt_terminal_projection_work
                GROUP BY work_status
                """, (RowCallbackHandler) rs -> counts.put(
                rs.getString("work_status"), rs.getLong("item_count")));
        for (String statusName : counts.keySet()) {
            try {
                Status.valueOf(statusName);
            } catch (IllegalArgumentException invalid) {
                throw conflict(ConflictReason.INTEGRITY_FAILURE);
            }
        }
        long due = number("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE work_status = ? AND next_attempt_at <= ?
                """, Status.READY.name(), Timestamp.from(now));
        long expired = number("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE work_status = ? AND lease_until <= ?
                """, Status.LEASED.name(), Timestamp.from(now));
        Instant oldest = jdbc.query("""
                SELECT MIN(actionable_at) FROM (
                    SELECT next_attempt_at AS actionable_at
                    FROM rg_test_stability_attempt_terminal_projection_work
                    WHERE work_status = ? AND next_attempt_at <= ?
                    UNION ALL
                    SELECT lease_until AS actionable_at
                    FROM rg_test_stability_attempt_terminal_projection_work
                    WHERE work_status = ? AND lease_until <= ?
                ) actionable
                """, rs -> rs.next() && rs.getTimestamp(1) != null
                        ? rs.getTimestamp(1).toInstant() : null,
                Status.READY.name(), Timestamp.from(now),
                Status.LEASED.name(), Timestamp.from(now));
        return new Snapshot(now,
                counts.getOrDefault(Status.READY.name(), 0L),
                counts.getOrDefault(Status.LEASED.name(), 0L),
                counts.getOrDefault(Status.COMPLETED.name(), 0L),
                counts.getOrDefault(Status.QUARANTINED.name(), 0L),
                due, expired, Optional.ofNullable(oldest));
    }

    private Entry firstDue(Instant now) {
        List<Entry> rows = jdbc.query(select() + """
                 WHERE (work_status = ? AND next_attempt_at <= ?)
                    OR (work_status = ? AND lease_until <= ?)
                 ORDER BY CASE WHEN work_status = ? THEN lease_until ELSE next_attempt_at END,
                          registered_at, work_id
                 FETCH FIRST 1 ROW ONLY
                 FOR UPDATE
                """, this::map, Status.READY.name(), Timestamp.from(now),
                Status.LEASED.name(), Timestamp.from(now), Status.LEASED.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Entry lock(String attemptId) {
        List<Entry> rows = jdbc.query(select()
                + " WHERE attempt_id = ? FOR UPDATE", this::map, attemptId);
        if (rows.size() > 1) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean eligible(Entry entry, Instant now) {
        return entry.status() == Status.READY && !entry.nextAttemptAt().isAfter(now)
                || entry.status() == Status.LEASED && !entry.leaseUntil().isAfter(now);
    }

    private boolean leaseMatches(Entry entry, Lease lease) {
        return entry.status() == Status.LEASED
                && entry.trigger().workId().equals(lease.workId())
                && entry.trigger().attemptId().equals(lease.attemptId())
                && entry.leaseOwner().equals(lease.ownerId())
                && entry.leaseToken().equals(lease.token())
                && entry.leaseEpoch() == lease.epoch()
                && entry.leaseClaimedAt().equals(lease.claimedAt())
                && entry.leaseUntil().equals(lease.leaseUntil());
    }

    private void register(JdbcTemplate transactionJdbc, Trigger trigger, String triggerJson) {
        Optional<Entry> retained = find(transactionJdbc, trigger.attemptId());
        if (retained.isPresent()) {
            if (!validate(retained.orElseThrow()).trigger().equals(trigger)) {
                throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
            }
            return;
        }
        Instant now = databaseNow(transactionJdbc);
        Entry entry = fingerprinted(new Entry(
                Entry.SCHEMA_VERSION, trigger, Status.READY, now,
                "", "", 0, Instant.EPOCH, Instant.EPOCH,
                0, 0, 0, ResultKind.NONE,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason.NONE,
                "", "", now, now, placeholderFingerprint()));
        try {
            insert(transactionJdbc, entry, triggerJson);
        } catch (DuplicateKeyException duplicate) {
            Optional<Entry> winner = find(transactionJdbc, trigger.attemptId());
            if (winner.isPresent() && validate(winner.orElseThrow()).trigger().equals(trigger)) {
                return;
            }
            throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
        }
    }

    private Optional<Entry> find(JdbcTemplate target, String attemptId) {
        List<Entry> rows = target.query("""
                SELECT attempt_id, tenant_id, environment_id, work_id,
                       trigger_fingerprint, trigger_json, observation_command_id,
                       reconciliation_result_fingerprint, work_status, next_attempt_at,
                       lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                       execution_attempts, consecutive_proof_pending,
                       consecutive_unavailable, last_result_kind, last_failure_reason,
                       projection_id, last_result_fingerprint, registered_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE attempt_id = ?
                """, this::map, attemptId);
        if (rows.size() > 1) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return rows.stream().findFirst();
    }

    private String select() {
        return """
                SELECT attempt_id, tenant_id, environment_id, work_id,
                       trigger_fingerprint, trigger_json, observation_command_id,
                       reconciliation_result_fingerprint, work_status, next_attempt_at,
                       lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                       execution_attempts, consecutive_proof_pending,
                       consecutive_unavailable, last_result_kind, last_failure_reason,
                       projection_id, last_result_fingerprint, registered_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_terminal_projection_work
                """;
    }

    private void insert(JdbcTemplate target, Entry entry, String triggerJson) {
        target.update("""
                INSERT INTO rg_test_stability_attempt_terminal_projection_work (
                    attempt_id, tenant_id, environment_id, work_id,
                    trigger_fingerprint, trigger_json, observation_command_id,
                    reconciliation_result_fingerprint, work_status, next_attempt_at,
                    lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                    execution_attempts, consecutive_proof_pending,
                    consecutive_unavailable, last_result_kind, last_failure_reason,
                    projection_id, last_result_fingerprint, registered_at, updated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.trigger().attemptId(), entry.trigger().tenantId(),
                entry.trigger().environmentId(), entry.trigger().workId(),
                entry.trigger().triggerFingerprint(), triggerJson,
                entry.trigger().observationCommandId(),
                entry.trigger().reconciliationResultFingerprint(), entry.status().name(),
                Timestamp.from(entry.nextAttemptAt()), entry.leaseOwner(), entry.leaseToken(),
                entry.leaseEpoch(), Timestamp.from(entry.leaseClaimedAt()),
                Timestamp.from(entry.leaseUntil()), entry.executionAttempts(),
                entry.consecutiveProofPending(), entry.consecutiveUnavailable(),
                entry.lastResultKind().name(), entry.lastFailureReason().name(),
                entry.projectionId(), entry.lastResultFingerprint(),
                Timestamp.from(entry.registeredAt()), Timestamp.from(entry.updatedAt()),
                entry.recordFingerprint());
    }

    private void update(Entry previous, Entry value) {
        int updated = jdbc.update("""
                UPDATE rg_test_stability_attempt_terminal_projection_work
                SET work_status = ?, next_attempt_at = ?, lease_owner = ?, lease_token = ?,
                    lease_epoch = ?, lease_claimed_at = ?, lease_until = ?,
                    execution_attempts = ?, consecutive_proof_pending = ?,
                    consecutive_unavailable = ?, last_result_kind = ?,
                    last_failure_reason = ?, projection_id = ?, last_result_fingerprint = ?,
                    updated_at = ?, record_fingerprint = ?
                WHERE attempt_id = ? AND record_fingerprint = ?
                """, value.status().name(), Timestamp.from(value.nextAttemptAt()),
                value.leaseOwner(), value.leaseToken(), value.leaseEpoch(),
                Timestamp.from(value.leaseClaimedAt()), Timestamp.from(value.leaseUntil()),
                value.executionAttempts(), value.consecutiveProofPending(),
                value.consecutiveUnavailable(), value.lastResultKind().name(),
                value.lastFailureReason().name(), value.projectionId(),
                value.lastResultFingerprint(), Timestamp.from(value.updatedAt()),
                value.recordFingerprint(), value.trigger().attemptId(),
                previous.recordFingerprint());
        if (updated != 1) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Entry map(ResultSet rs, int row) throws SQLException {
        try {
            Trigger trigger = decode(rs.getString("trigger_json"));
            if (!trigger.attemptId().equals(rs.getString("attempt_id"))
                    || !trigger.tenantId().equals(rs.getString("tenant_id"))
                    || !trigger.environmentId().equals(rs.getString("environment_id"))
                    || !trigger.workId().equals(rs.getString("work_id"))
                    || !trigger.triggerFingerprint().equals(
                    rs.getString("trigger_fingerprint"))
                    || !trigger.observationCommandId().equals(
                    rs.getString("observation_command_id"))
                    || !trigger.reconciliationResultFingerprint().equals(
                    rs.getString("reconciliation_result_fingerprint"))) {
                throw conflict(ConflictReason.INTEGRITY_FAILURE);
            }
            return new Entry(Entry.SCHEMA_VERSION, trigger,
                    Status.valueOf(rs.getString("work_status")),
                    rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getString("lease_owner"), rs.getString("lease_token"),
                    rs.getLong("lease_epoch"),
                    rs.getTimestamp("lease_claimed_at").toInstant(),
                    rs.getTimestamp("lease_until").toInstant(),
                    rs.getLong("execution_attempts"),
                    rs.getInt("consecutive_proof_pending"),
                    rs.getInt("consecutive_unavailable"),
                    ResultKind.valueOf(rs.getString("last_result_kind")),
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .valueOf(rs.getString("last_failure_reason")),
                    rs.getString("projection_id"),
                    rs.getString("last_result_fingerprint"),
                    rs.getTimestamp("registered_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("record_fingerprint"));
        } catch (RuntimeException invalid) {
            if (invalid instanceof ConflictException conflict) {
                throw conflict;
            }
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Entry validate(Entry entry) {
        try {
            Trigger trigger = requireTrigger(entry.trigger());
            String expected = entryFingerprint(new Entry(
                    entry.schemaVersion(), trigger, entry.status(), entry.nextAttemptAt(),
                    entry.leaseOwner(), entry.leaseToken(), entry.leaseEpoch(),
                    entry.leaseClaimedAt(), entry.leaseUntil(), entry.executionAttempts(),
                    entry.consecutiveProofPending(), entry.consecutiveUnavailable(),
                    entry.lastResultKind(), entry.lastFailureReason(), entry.projectionId(),
                    entry.lastResultFingerprint(), entry.registeredAt(), entry.updatedAt(),
                    placeholderFingerprint()));
            if (!entry.recordFingerprint().equals(expected)) {
                throw conflict(ConflictReason.INTEGRITY_FAILURE);
            }
            return entry;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ConflictException conflict) {
                throw conflict;
            }
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Trigger requireTrigger(Trigger trigger) {
        Trigger exact = Objects.requireNonNull(trigger, "trigger");
        String expected = ProtocolFingerprint.of(objectMapper, exact.canonicalMaterial());
        if (!expected.equals(exact.triggerFingerprint())) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return exact;
    }

    private Lease requireLease(Lease lease) {
        Lease exact = Objects.requireNonNull(lease, "lease");
        String expected = leaseFingerprint(
                exact.workId(), exact.attemptId(), exact.ownerId(), exact.token(),
                exact.epoch(), exact.claimedAt(), exact.leaseUntil());
        if (!expected.equals(exact.fenceFingerprint())) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return exact;
    }

    private Entry fingerprinted(Entry entry) {
        return new Entry(entry.schemaVersion(), entry.trigger(), entry.status(),
                entry.nextAttemptAt(), entry.leaseOwner(), entry.leaseToken(),
                entry.leaseEpoch(), entry.leaseClaimedAt(), entry.leaseUntil(),
                entry.executionAttempts(), entry.consecutiveProofPending(),
                entry.consecutiveUnavailable(), entry.lastResultKind(),
                entry.lastFailureReason(), entry.projectionId(),
                entry.lastResultFingerprint(), entry.registeredAt(), entry.updatedAt(),
                entryFingerprint(entry));
    }

    private String entryFingerprint(Entry entry) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", ENTRY_FINGERPRINT_SCHEMA);
        material.put("triggerFingerprint", entry.trigger().triggerFingerprint());
        material.put("status", entry.status());
        material.put("nextAttemptAt", entry.nextAttemptAt());
        material.put("leaseOwner", entry.leaseOwner());
        material.put("leaseToken", entry.leaseToken());
        material.put("leaseEpoch", entry.leaseEpoch());
        material.put("leaseClaimedAt", entry.leaseClaimedAt());
        material.put("leaseUntil", entry.leaseUntil());
        material.put("executionAttempts", entry.executionAttempts());
        material.put("consecutiveProofPending", entry.consecutiveProofPending());
        material.put("consecutiveUnavailable", entry.consecutiveUnavailable());
        material.put("lastResultKind", entry.lastResultKind());
        material.put("lastFailureReason", entry.lastFailureReason());
        material.put("projectionId", entry.projectionId());
        material.put("lastResultFingerprint", entry.lastResultFingerprint());
        material.put("registeredAt", entry.registeredAt());
        material.put("updatedAt", entry.updatedAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String leaseFingerprint(Entry entry) {
        return leaseFingerprint(entry.trigger().workId(), entry.trigger().attemptId(),
                entry.leaseOwner(), entry.leaseToken(), entry.leaseEpoch(),
                entry.leaseClaimedAt(), entry.leaseUntil());
    }

    private String leaseFingerprint(
            String workId,
            String attemptId,
            String owner,
            String token,
            long epoch,
            Instant claimedAt,
            Instant leaseUntil) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkLease.v1");
        material.put("workId", workId);
        material.put("attemptId", attemptId);
        material.put("ownerId", owner);
        material.put("token", token);
        material.put("epoch", epoch);
        material.put("claimedAt", claimedAt);
        material.put("leaseUntil", leaseUntil);
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String resultFingerprint(Lease lease, Result result) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkResultFence.v1");
        material.put("leaseFenceFingerprint", lease.fenceFingerprint());
        material.put("result", result.canonicalMaterial());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private Duration retryDelay(Duration initial, int consecutive) {
        Duration delay = initial;
        for (int index = 1; index < consecutive; index++) {
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

    private long number(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        if (value == null || value < 0) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return value;
    }

    private Instant databaseNow(JdbcTemplate target) {
        Timestamp value = target.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no value");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant safePlus(Instant value, Duration duration) {
        try {
            return value.plus(duration).truncatedTo(ChronoUnit.MILLIS);
        } catch (ArithmeticException invalid) {
            throw new IllegalStateException("Projection-work database time overflow", invalid);
        }
    }

    private static long increment(long value, String field) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException(field + " exhausted");
        }
        return value + 1;
    }

    private static int incrementInt(int value, String field) {
        if (value == Integer.MAX_VALUE) {
            throw new IllegalStateException(field + " exhausted");
        }
        return value + 1;
    }

    private String encode(Trigger trigger) {
        try {
            return objectMapper.writeValueAsString(trigger);
        } catch (JsonProcessingException invalid) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Trigger decode(String value) {
        try {
            return objectMapper.readValue(value, Trigger.class);
        } catch (JsonProcessingException invalid) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private static String placeholderFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static ConflictException conflict(ConflictReason reason) {
        return new ConflictException(reason);
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        if (jdbc == null || jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("jdbc datasource is required");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }
}
