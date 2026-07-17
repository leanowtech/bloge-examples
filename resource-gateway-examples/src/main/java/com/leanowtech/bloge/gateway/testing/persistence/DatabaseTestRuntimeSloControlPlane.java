package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Database-clock operational read model for the complete isolated test runtime.
 *
 * <p>The snapshot is assembled in one read-only, repeatable-read transaction. Queries use only
 * bounded lifecycle projections and aggregate counts; they never deserialize evidence, fixture,
 * checkpoint, work-item, or business payload JSON. Unknown persisted lifecycle labels fail closed
 * instead of creating unbounded metric labels.</p>
 */
public final class DatabaseTestRuntimeSloControlPlane {

    private static final Duration MAXIMUM_OUTCOME_LOOKBACK = Duration.ofDays(365);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate observations;

    /**
     * Creates an operational reader over the isolated test-runtime transaction boundary.
     *
     * @param jdbc test-runtime JDBC facade
     * @param transactionManager transaction manager for the same datasource
     */
    public DatabaseTestRuntimeSloControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        observations = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        observations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        observations.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        observations.setReadOnly(true);
    }

    /**
     * Observes recent outcomes, active control queues, and retained-record pressure atomically.
     *
     * @param outcomeLookback positive recent-outcome window
     * @return payload-free aggregate snapshot evaluated against the database clock
     */
    public OperationalSnapshot operationalSnapshot(Duration outcomeLookback) {
        Duration window = positive(outcomeLookback, "outcomeLookback");
        if (window.compareTo(MAXIMUM_OUTCOME_LOOKBACK) > 0) {
            throw new IllegalArgumentException("outcomeLookback must not exceed 365 days");
        }
        OperationalSnapshot snapshot = observations.execute(status -> {
            Instant observedAt = currentTime();
            Instant cutoff = observedAt.minus(window);
            Map<TestRunEvidence.Status, Long> executions = enumCounts(
                    TestRunEvidence.Status.class,
                    """
                            SELECT status, COUNT(*) AS aggregate_count
                            FROM rg_test_run_records
                            WHERE created_at >= ? AND created_at <= ?
                            GROUP BY status
                            """,
                    Timestamp.from(cutoff), Timestamp.from(observedAt));
            Map<TestSuiteRunEvidence.Status, Long> suites = enumCounts(
                    TestSuiteRunEvidence.Status.class,
                    """
                            SELECT status, COUNT(*) AS aggregate_count
                            FROM rg_test_suite_run_records
                            WHERE status <> 'RUNNING'
                              AND last_checkpoint_at >= ? AND last_checkpoint_at <= ?
                            GROUP BY status
                            """,
                    Timestamp.from(cutoff), Timestamp.from(observedAt));
            Map<DurableTestExecutionCheckpoint.Status, Long> durableExecutions = enumCounts(
                    DurableTestExecutionCheckpoint.Status.class,
                    """
                            SELECT status, COUNT(*) AS aggregate_count
                            FROM rg_test_durable_execution_checkpoints
                            GROUP BY status
                            """);
            Map<ExecutionStatus, Long> engineExecutions = enumCounts(
                    ExecutionStatus.class,
                    """
                            SELECT execution_status AS status, COUNT(*) AS aggregate_count
                            FROM rg_test_bloge_executions
                            GROUP BY execution_status
                            """);
            Map<WorkItemStatus, Long> workItems = enumCounts(
                    WorkItemStatus.class,
                    """
                            SELECT item_status AS status, COUNT(*) AS aggregate_count
                            FROM rg_test_bloge_work_items
                            GROUP BY item_status
                            """);
            return new OperationalSnapshot(
                    observedAt,
                    window,
                    executions,
                    suites,
                    durableExecutions,
                    engineExecutions,
                    workItems,
                    suiteQueue(observedAt),
                    creationQueue(observedAt),
                    durableQueue(observedAt),
                    workQueue(observedAt),
                    workerCandidateDeferrals(observedAt),
                    workerCandidateQuarantines(observedAt),
                    storage(observedAt));
        });
        if (snapshot == null) {
            throw new IllegalStateException("Test-runtime operational snapshot transaction returned no result");
        }
        return snapshot;
    }

    private QueueSnapshot suiteQueue(Instant observedAt) {
        return queue("""
                SELECT COUNT(*) AS queue_depth,
                       COALESCE(SUM(CASE WHEN lease_expires_at <= ? THEN 1 ELSE 0 END), 0)
                           AS expired_claims,
                       MIN(last_checkpoint_at) AS oldest_activity
                FROM rg_test_suite_run_records
                WHERE status = 'RUNNING' AND expires_at > ?
                """, observedAt, observedAt);
    }

    private QueueSnapshot creationQueue(Instant observedAt) {
        return queue("""
                SELECT COUNT(*) AS queue_depth,
                       COALESCE(SUM(CASE WHEN lease_expires_at <= ? THEN 1 ELSE 0 END), 0)
                           AS expired_claims,
                       MIN(updated_at) AS oldest_activity
                FROM rg_test_durable_creation_commands
                WHERE state = 'PENDING'
                """, observedAt);
    }

    private QueueSnapshot durableQueue(Instant observedAt) {
        return queue("""
                SELECT COUNT(*) AS queue_depth,
                       COALESCE(SUM(CASE
                           WHEN status IN ('ACTIVE', 'RESUMING') AND lease_expires_at <= ?
                           THEN 1 ELSE 0 END), 0) AS expired_claims,
                       MIN(CASE WHEN status IN ('ACTIVE', 'RESUMING')
                           THEN updated_at ELSE NULL END) AS oldest_activity
                FROM rg_test_durable_execution_checkpoints
                WHERE status IN ('ACTIVE', 'SUSPENDED', 'RESUMING')
                """, observedAt);
    }

    private QueueSnapshot workQueue(Instant observedAt) {
        return queue("""
                SELECT COUNT(*) AS queue_depth,
                       COALESCE(SUM(CASE
                           WHEN item_status = 'CLAIMED' AND claim_until <= ?
                           THEN 1 ELSE 0 END), 0) AS expired_claims,
                       MIN(created_at) AS oldest_activity
                FROM rg_test_bloge_work_items
                WHERE item_status = 'READY'
                   OR (item_status = 'RETRY_WAIT'
                       AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                   OR (item_status = 'CLAIMED' AND claim_until <= ?)
                """, observedAt, observedAt, observedAt);
    }

    private WorkerCandidateDeferralSnapshot workerCandidateDeferrals(Instant observedAt) {
        EnumMap<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                totals = new EnumMap<>(
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason.class);
        EnumMap<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                active = new EnumMap<>(
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason.class);
        for (var reason : DurableTestExecutionCheckpointRepository
                .WorkerCandidateDeferralReason.values()) {
            totals.put(reason, 0L);
            active.put(reason, 0L);
        }
        Instant[] oldestActive = new Instant[1];
        long[] maximumConsecutiveFailures = new long[1];
        jdbc.query("""
                SELECT reason,
                       COUNT(*) AS total_count,
                       COALESCE(SUM(CASE WHEN retry_after > ? THEN 1 ELSE 0 END), 0)
                           AS active_count,
                       MIN(CASE WHEN retry_after > ? THEN first_observed_at ELSE NULL END)
                           AS oldest_active,
                       COALESCE(MAX(CASE WHEN retry_after > ? THEN consecutive_failures ELSE 0 END), 0)
                           AS maximum_consecutive_failures
                FROM rg_test_durable_worker_candidate_deferrals
                GROUP BY reason
                """, rs -> {
            DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason reason;
            try {
                reason = DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .valueOf(rs.getString("reason"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "Stored worker deferral reason is outside its closed vocabulary");
            }
            totals.put(reason, rs.getLong("total_count"));
            active.put(reason, rs.getLong("active_count"));
            Timestamp candidateOldest = rs.getTimestamp("oldest_active");
            if (candidateOldest != null && (oldestActive[0] == null
                    || candidateOldest.toInstant().isBefore(oldestActive[0]))) {
                oldestActive[0] = candidateOldest.toInstant();
            }
            maximumConsecutiveFailures[0] = Math.max(
                    maximumConsecutiveFailures[0],
                    rs.getLong("maximum_consecutive_failures"));
        }, Timestamp.from(observedAt), Timestamp.from(observedAt), Timestamp.from(observedAt));
        return new WorkerCandidateDeferralSnapshot(
                Map.copyOf(totals), Map.copyOf(active), oldestActive[0],
                maximumConsecutiveFailures[0]);
    }

    private WorkerCandidateQuarantineSnapshot workerCandidateQuarantines(Instant observedAt) {
        EnumMap<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                totals = new EnumMap<>(
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason.class);
        for (var reason : DurableTestExecutionCheckpointRepository
                .WorkerCandidateDeferralReason.values()) {
            totals.put(reason, 0L);
        }
        Instant[] oldest = new Instant[1];
        long[] maximumConsecutiveFailures = new long[1];
        EnumMap<DatabaseDurableWorkerQuarantineControlPlane.QuarantineState, Long>
                maintenanceStates = new EnumMap<>(
                DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.class);
        for (var state : DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.values()) {
            maintenanceStates.put(state, 0L);
        }
        long[] expiredClaims = new long[1];
        jdbc.query("""
                SELECT q.reason, COUNT(*) AS total_count,
                       MIN(q.quarantined_at) AS oldest_quarantined,
                       COALESCE(MAX(q.consecutive_failures), 0) AS maximum_consecutive_failures,
                       COALESCE(SUM(CASE
                           WHEN c.control_state = 'CLAIMED' AND c.claim_until > ?
                           THEN 1 ELSE 0 END), 0) AS claimed_count,
                       COALESCE(SUM(CASE
                           WHEN c.scope_key IS NULL OR c.control_state = 'AVAILABLE'
                                OR (c.control_state = 'CLAIMED' AND c.claim_until <= ?)
                           THEN 1 ELSE 0 END), 0) AS available_count,
                       COALESCE(SUM(CASE
                           WHEN c.control_state = 'CLAIMED' AND c.claim_until <= ?
                           THEN 1 ELSE 0 END), 0) AS expired_claim_count
                FROM rg_test_durable_worker_candidate_quarantines q
                LEFT JOIN rg_test_durable_worker_quarantine_controls c
                  ON c.scope_key = q.scope_key AND c.run_id = q.run_id
                GROUP BY q.reason
                """, rs -> {
            DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason reason;
            try {
                reason = DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .valueOf(rs.getString("reason"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "Stored worker quarantine reason is outside its closed vocabulary");
            }
            totals.put(reason, rs.getLong("total_count"));
            Timestamp candidateOldest = rs.getTimestamp("oldest_quarantined");
            if (candidateOldest != null && (oldest[0] == null
                    || candidateOldest.toInstant().isBefore(oldest[0]))) {
                oldest[0] = candidateOldest.toInstant();
            }
            maximumConsecutiveFailures[0] = Math.max(
                    maximumConsecutiveFailures[0],
                    rs.getLong("maximum_consecutive_failures"));
            long availableCount = rs.getLong("available_count");
            long claimedCount = rs.getLong("claimed_count");
            maintenanceStates.compute(
                    DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.AVAILABLE,
                    (ignored, count) -> count + availableCount);
            maintenanceStates.compute(
                    DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.CLAIMED,
                    (ignored, count) -> count + claimedCount);
            expiredClaims[0] += rs.getLong("expired_claim_count");
        }, Timestamp.from(observedAt), Timestamp.from(observedAt),
                Timestamp.from(observedAt));
        Long historyRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_history", Long.class);
        Long liveDiscardApprovals = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_discard_approvals
                WHERE approval_state = 'APPROVED' AND approval_until > ?
                """, Long.class, Timestamp.from(observedAt));
        Long expiredDiscardApprovals = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_discard_approvals
                WHERE approval_state = 'APPROVED' AND approval_until <= ?
                """, Long.class, Timestamp.from(observedAt));
        Long approvedDiscardHistoryRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_discard_history",
                Long.class);
        return new WorkerCandidateQuarantineSnapshot(
                Map.copyOf(totals), oldest[0], maximumConsecutiveFailures[0],
                Map.copyOf(maintenanceStates), expiredClaims[0],
                historyRecords == null ? 0 : historyRecords,
                liveDiscardApprovals == null ? 0 : liveDiscardApprovals,
                expiredDiscardApprovals == null ? 0 : expiredDiscardApprovals,
                approvedDiscardHistoryRecords == null ? 0 : approvedDiscardHistoryRecords);
    }

    private QueueSnapshot queue(String sql, Instant... instants) {
        Object[] arguments = java.util.Arrays.stream(instants)
                .map(Timestamp::from)
                .toArray();
        QueueSnapshot result = jdbc.queryForObject(sql, (rs, row) -> {
            Timestamp oldest = rs.getTimestamp("oldest_activity");
            return new QueueSnapshot(
                    rs.getLong("queue_depth"),
                    rs.getLong("expired_claims"),
                    oldest == null ? null : oldest.toInstant());
        }, arguments);
        if (result == null) {
            throw new IllegalStateException("Test-runtime queue observation returned no result");
        }
        return result;
    }

    private StorageSnapshot storage(Instant observedAt) {
        RecordCounts executions = recordCounts("rg_test_run_records", observedAt);
        RecordCounts suites = recordCounts("rg_test_suite_run_records", observedAt);
        long terminalDurable = count("""
                SELECT COUNT(*) FROM rg_test_durable_execution_checkpoints
                WHERE status IN ('TERMINAL', 'CONTROL_PLAN_UNAVAILABLE')
                """);
        long terminalWorkItems = count("""
                SELECT COUNT(*) FROM rg_test_bloge_work_items
                WHERE item_status IN ('DONE', 'FAILED', 'DEAD_LETTER', 'CANCELLED')
                """);
        return new StorageSnapshot(
                executions.total(), executions.expired(),
                suites.total(), suites.expired(),
                terminalDurable, terminalWorkItems);
    }

    private RecordCounts recordCounts(String table, Instant observedAt) {
        RecordCounts result = jdbc.queryForObject("""
                        SELECT COUNT(*) AS total_records,
                               COALESCE(SUM(CASE WHEN expires_at <= ? THEN 1 ELSE 0 END), 0)
                                   AS expired_records
                        FROM %s
                        """.formatted(table),
                (rs, row) -> new RecordCounts(
                        rs.getLong("total_records"), rs.getLong("expired_records")),
                Timestamp.from(observedAt));
        if (result == null) {
            throw new IllegalStateException("Test-runtime storage observation returned no result");
        }
        return result;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        if (value == null) {
            throw new IllegalStateException("Test-runtime aggregate count returned no result");
        }
        return value;
    }

    private Instant currentTime() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Test-runtime database did not return its current time");
        }
        return value.toInstant();
    }

    private <E extends Enum<E>> Map<E, Long> enumCounts(
            Class<E> type,
            String sql,
            Object... arguments) {
        EnumMap<E, Long> counts = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counts.put(value, 0L);
        }
        jdbc.query(sql, rs -> {
            E value;
            try {
                value = Enum.valueOf(type, rs.getString("status"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "Stored lifecycle status is outside its closed vocabulary");
            }
            counts.put(value, rs.getLong("aggregate_count"));
        }, arguments);
        return Map.copyOf(counts);
    }

    private static Duration positive(Duration value, String name) {
        Duration result = Objects.requireNonNull(value, name);
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }

    /**
     * One payload-free operational observation.
     *
     * @param observedAt persistence-authoritative observation time
     * @param outcomeLookback recent terminal-outcome window
     * @param executionOutcomes recent child execution counts by closed status
     * @param suiteOutcomes recent terminal suite counts by closed status
     * @param durableExecutionStates all durable control checkpoints by closed state
     * @param engineExecutionStates all BLOGE execution rows by closed state
     * @param workItemStates all BLOGE work-item rows by closed state
     * @param suiteRuns active suite-run ownership queue
     * @param durableCreations pending durable-creation ownership queue
     * @param durableExecutions resumable durable execution queue
     * @param workItems dispatchable or expired-claim work queue
     * @param workerCandidateDeferrals deterministic worker authorization backoff pressure
     * @param workerCandidateQuarantines permanently ineligible exact-checkpoint pressure
     * @param storage retained-record pressure
     */
    public record OperationalSnapshot(
            Instant observedAt,
            Duration outcomeLookback,
            Map<TestRunEvidence.Status, Long> executionOutcomes,
            Map<TestSuiteRunEvidence.Status, Long> suiteOutcomes,
            Map<DurableTestExecutionCheckpoint.Status, Long> durableExecutionStates,
            Map<ExecutionStatus, Long> engineExecutionStates,
            Map<WorkItemStatus, Long> workItemStates,
            QueueSnapshot suiteRuns,
            QueueSnapshot durableCreations,
            QueueSnapshot durableExecutions,
            QueueSnapshot workItems,
            WorkerCandidateDeferralSnapshot workerCandidateDeferrals,
            WorkerCandidateQuarantineSnapshot workerCandidateQuarantines,
            StorageSnapshot storage) {
        /** Creates a compatibility snapshot with no worker candidate deferral pressure. */
        public OperationalSnapshot(
                Instant observedAt,
                Duration outcomeLookback,
                Map<TestRunEvidence.Status, Long> executionOutcomes,
                Map<TestSuiteRunEvidence.Status, Long> suiteOutcomes,
                Map<DurableTestExecutionCheckpoint.Status, Long> durableExecutionStates,
                Map<ExecutionStatus, Long> engineExecutionStates,
                Map<WorkItemStatus, Long> workItemStates,
                QueueSnapshot suiteRuns,
                QueueSnapshot durableCreations,
                QueueSnapshot durableExecutions,
                QueueSnapshot workItems,
                StorageSnapshot storage) {
            this(observedAt, outcomeLookback, executionOutcomes, suiteOutcomes,
                    durableExecutionStates, engineExecutionStates, workItemStates,
                    suiteRuns, durableCreations, durableExecutions, workItems,
                    WorkerCandidateDeferralSnapshot.empty(),
                    WorkerCandidateQuarantineSnapshot.empty(), storage);
        }

        /** Creates a compatibility snapshot with no worker candidate quarantine pressure. */
        public OperationalSnapshot(
                Instant observedAt,
                Duration outcomeLookback,
                Map<TestRunEvidence.Status, Long> executionOutcomes,
                Map<TestSuiteRunEvidence.Status, Long> suiteOutcomes,
                Map<DurableTestExecutionCheckpoint.Status, Long> durableExecutionStates,
                Map<ExecutionStatus, Long> engineExecutionStates,
                Map<WorkItemStatus, Long> workItemStates,
                QueueSnapshot suiteRuns,
                QueueSnapshot durableCreations,
                QueueSnapshot durableExecutions,
                QueueSnapshot workItems,
                WorkerCandidateDeferralSnapshot workerCandidateDeferrals,
                StorageSnapshot storage) {
            this(observedAt, outcomeLookback, executionOutcomes, suiteOutcomes,
                    durableExecutionStates, engineExecutionStates, workItemStates,
                    suiteRuns, durableCreations, durableExecutions, workItems,
                    workerCandidateDeferrals, WorkerCandidateQuarantineSnapshot.empty(), storage);
        }

        /** Freezes all aggregate maps and rejects incomplete observations. */
        public OperationalSnapshot {
            Objects.requireNonNull(observedAt, "observedAt");
            positive(outcomeLookback, "outcomeLookback");
            executionOutcomes = Map.copyOf(Objects.requireNonNull(
                    executionOutcomes, "executionOutcomes"));
            suiteOutcomes = Map.copyOf(Objects.requireNonNull(suiteOutcomes, "suiteOutcomes"));
            durableExecutionStates = Map.copyOf(Objects.requireNonNull(
                    durableExecutionStates, "durableExecutionStates"));
            engineExecutionStates = Map.copyOf(Objects.requireNonNull(
                    engineExecutionStates, "engineExecutionStates"));
            workItemStates = Map.copyOf(Objects.requireNonNull(workItemStates, "workItemStates"));
            Objects.requireNonNull(suiteRuns, "suiteRuns");
            Objects.requireNonNull(durableCreations, "durableCreations");
            Objects.requireNonNull(durableExecutions, "durableExecutions");
            Objects.requireNonNull(workItems, "workItems");
            Objects.requireNonNull(workerCandidateDeferrals, "workerCandidateDeferrals");
            Objects.requireNonNull(workerCandidateQuarantines, "workerCandidateQuarantines");
            Objects.requireNonNull(storage, "storage");
        }

        /**
         * Counts recent terminal child executions.
         *
         * @return number of recent terminal child executions
         */
        public long executionSamples() {
            return executionOutcomes.values().stream().mapToLong(Long::longValue).sum();
        }

        /**
         * Counts recent child executions whose correctness evidence is unusable.
         *
         * @return recent incomplete child execution count
         */
        public long incompleteExecutions() {
            return executionOutcomes.getOrDefault(TestRunEvidence.Status.EVIDENCE_INCOMPLETE, 0L)
                    + executionOutcomes.getOrDefault(
                    TestRunEvidence.Status.CONTROL_PLAN_UNAVAILABLE, 0L);
        }

        /**
         * Counts recent terminal suite executions.
         *
         * @return number of recent terminal suite executions
         */
        public long suiteSamples() {
            return suiteOutcomes.values().stream().mapToLong(Long::longValue).sum();
        }

        /**
         * Counts recent suites that did not produce a complete aggregate closure.
         *
         * @return recent incomplete suite count
         */
        public long incompleteSuites() {
            return suiteOutcomes.getOrDefault(TestSuiteRunEvidence.Status.PARTIAL, 0L)
                    + suiteOutcomes.getOrDefault(
                    TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE, 0L);
        }
    }

    /**
     * Aggregate queue pressure without tenant, owner, run, or item identities.
     *
     * @param depth actionable or resumable entries
     * @param expiredClaims entries whose active ownership fence expired
     * @param oldestActivityAt oldest queue activity relevant to staleness, or {@code null}
     */
    public record QueueSnapshot(long depth, long expiredClaims, Instant oldestActivityAt) {
        /** Rejects impossible negative aggregate counts. */
        public QueueSnapshot {
            if (depth < 0 || expiredClaims < 0 || expiredClaims > depth) {
                throw new IllegalArgumentException("Invalid test-runtime queue aggregate");
            }
        }
    }

    /**
     * Payload-free deterministic worker-candidate backoff pressure.
     *
     * @param totalByReason retained records by closed reason
     * @param activeByReason records whose database retry deadline remains in the future
     * @param oldestActiveObservedAt first observation of the oldest active record, or {@code null}
     * @param maximumActiveConsecutiveFailures largest active same-reason failure count
     */
    public record WorkerCandidateDeferralSnapshot(
            Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                    totalByReason,
            Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                    activeByReason,
            Instant oldestActiveObservedAt,
            long maximumActiveConsecutiveFailures) {
        /** Freezes the closed maps and rejects inconsistent aggregates. */
        public WorkerCandidateDeferralSnapshot {
            totalByReason = Map.copyOf(Objects.requireNonNull(totalByReason, "totalByReason"));
            activeByReason = Map.copyOf(Objects.requireNonNull(activeByReason, "activeByReason"));
            for (var reason : DurableTestExecutionCheckpointRepository
                    .WorkerCandidateDeferralReason.values()) {
                long total = totalByReason.getOrDefault(reason, -1L);
                long active = activeByReason.getOrDefault(reason, -1L);
                if (total < 0 || active < 0 || active > total) {
                    throw new IllegalArgumentException(
                            "Invalid worker candidate deferral aggregate");
                }
            }
            long activeRecords = activeByReason.values().stream()
                    .mapToLong(Long::longValue).sum();
            if (maximumActiveConsecutiveFailures < 0
                    || (activeRecords == 0) != (oldestActiveObservedAt == null)
                    || (activeRecords == 0) != (maximumActiveConsecutiveFailures == 0)) {
                throw new IllegalArgumentException(
                        "Invalid worker candidate deferral activity aggregate");
            }
        }

        /** @return all retained deterministic-candidate records */
        public long totalRecords() {
            return totalByReason.values().stream().mapToLong(Long::longValue).sum();
        }

        /** @return records whose retry deadline remains in the future */
        public long activeRecords() {
            return activeByReason.values().stream().mapToLong(Long::longValue).sum();
        }

        /** @return records whose retry deadline is due and await another cyclic scan */
        public long retryDueRecords() {
            return totalRecords() - activeRecords();
        }

        /** @return an all-zero closed-vocabulary observation */
        public static WorkerCandidateDeferralSnapshot empty() {
            EnumMap<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                    empty = new EnumMap<>(
                    DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason.class);
            for (var reason : DurableTestExecutionCheckpointRepository
                    .WorkerCandidateDeferralReason.values()) {
                empty.put(reason, 0L);
            }
            return new WorkerCandidateDeferralSnapshot(
                    Map.copyOf(empty), Map.copyOf(empty), null, 0);
        }
    }

    /**
     * Payload-free permanent worker-candidate quarantine pressure.
     *
     * @param totalByReason active exact-checkpoint quarantines by closed reason
     * @param oldestQuarantinedAt database time of the oldest active quarantine, or {@code null}
     * @param maximumConsecutiveFailures largest threshold-crossing count
     * @param totalByMaintenanceState active records by effective database-clock owner state
     * @param expiredClaimRecords expired claims projected as available for takeover
     * @param historyRecords retained token-free manual action evidence
     * @param liveDiscardApprovals unconsumed checker approvals valid at database time
     * @param expiredDiscardApprovals unconsumed checker approvals past their deadline
     * @param approvedDiscardHistoryRecords retained token-free maker-checker evidence
     */
    public record WorkerCandidateQuarantineSnapshot(
            Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                    totalByReason,
            Instant oldestQuarantinedAt,
            long maximumConsecutiveFailures,
            Map<DatabaseDurableWorkerQuarantineControlPlane.QuarantineState, Long>
                    totalByMaintenanceState,
            long expiredClaimRecords,
            long historyRecords,
            long liveDiscardApprovals,
            long expiredDiscardApprovals,
            long approvedDiscardHistoryRecords) {
        /** Creates a compatibility observation without discard-approval lifecycle counts. */
        public WorkerCandidateQuarantineSnapshot(
                Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                        totalByReason,
                Instant oldestQuarantinedAt,
                long maximumConsecutiveFailures,
                Map<DatabaseDurableWorkerQuarantineControlPlane.QuarantineState, Long>
                        totalByMaintenanceState,
                long expiredClaimRecords,
                long historyRecords) {
            this(totalByReason, oldestQuarantinedAt, maximumConsecutiveFailures,
                    totalByMaintenanceState, expiredClaimRecords, historyRecords, 0, 0, 0);
        }

        /** Creates a compatibility observation with every active record available and no history. */
        public WorkerCandidateQuarantineSnapshot(
                Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                        totalByReason,
                Instant oldestQuarantinedAt,
                long maximumConsecutiveFailures) {
            this(totalByReason, oldestQuarantinedAt, maximumConsecutiveFailures,
                    allAvailable(totalByReason), 0, 0, 0, 0, 0);
        }

        /** Freezes the closed map and rejects inconsistent aggregates. */
        public WorkerCandidateQuarantineSnapshot {
            totalByReason = Map.copyOf(Objects.requireNonNull(totalByReason, "totalByReason"));
            for (var reason : DurableTestExecutionCheckpointRepository
                    .WorkerCandidateDeferralReason.values()) {
                Long value = totalByReason.get(reason);
                if (value == null || value < 0) {
                    throw new IllegalArgumentException(
                            "Worker candidate quarantine map must contain non-negative closed reasons");
                }
            }
            long records = totalByReason.values().stream().mapToLong(Long::longValue).sum();
            totalByMaintenanceState = Map.copyOf(Objects.requireNonNull(
                    totalByMaintenanceState, "totalByMaintenanceState"));
            for (var state : DatabaseDurableWorkerQuarantineControlPlane
                    .QuarantineState.values()) {
                Long value = totalByMaintenanceState.get(state);
                if (value == null || value < 0) {
                    throw new IllegalArgumentException(
                            "Worker quarantine maintenance map must contain non-negative closed states");
                }
            }
            long maintenanceRecords = totalByMaintenanceState.values().stream()
                    .mapToLong(Long::longValue).sum();
            if (totalByReason.size() != DurableTestExecutionCheckpointRepository
                    .WorkerCandidateDeferralReason.values().length
                    || totalByMaintenanceState.size()
                    != DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.values().length
                    || maximumConsecutiveFailures < 0
                    || (records == 0) != (oldestQuarantinedAt == null)
                    || (records == 0) != (maximumConsecutiveFailures == 0)
                    || maintenanceRecords != records || expiredClaimRecords < 0
                    || expiredClaimRecords > totalByMaintenanceState.get(
                    DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.AVAILABLE)
                    || historyRecords < 0 || liveDiscardApprovals < 0
                    || expiredDiscardApprovals < 0 || approvedDiscardHistoryRecords < 0) {
                throw new IllegalArgumentException(
                        "Invalid worker candidate quarantine aggregate");
            }
        }

        /** @return all active exact-checkpoint quarantine records */
        public long totalRecords() {
            return totalByReason.values().stream().mapToLong(Long::longValue).sum();
        }

        /** @return an all-zero closed-vocabulary observation */
        public static WorkerCandidateQuarantineSnapshot empty() {
            EnumMap<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long>
                    empty = new EnumMap<>(
                    DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason.class);
            for (var reason : DurableTestExecutionCheckpointRepository
                    .WorkerCandidateDeferralReason.values()) {
                empty.put(reason, 0L);
            }
            return new WorkerCandidateQuarantineSnapshot(
                    Map.copyOf(empty), null, 0, allAvailable(empty), 0, 0, 0, 0, 0);
        }

        private static Map<DatabaseDurableWorkerQuarantineControlPlane.QuarantineState, Long>
                allAvailable(Map<?, Long> totalByReason) {
            long records = totalByReason == null ? 0
                    : totalByReason.values().stream().mapToLong(Long::longValue).sum();
            EnumMap<DatabaseDurableWorkerQuarantineControlPlane.QuarantineState, Long> states =
                    new EnumMap<>(DatabaseDurableWorkerQuarantineControlPlane
                            .QuarantineState.class);
            states.put(DatabaseDurableWorkerQuarantineControlPlane
                    .QuarantineState.AVAILABLE, records);
            states.put(DatabaseDurableWorkerQuarantineControlPlane
                    .QuarantineState.CLAIMED, 0L);
            return Map.copyOf(states);
        }
    }

    /**
     * Aggregate storage pressure across retained correctness and durable-runtime rows.
     *
     * @param executionRecords all child execution evidence rows
     * @param expiredExecutionRecords child rows beyond retention
     * @param suiteRecords all suite execution rows
     * @param expiredSuiteRecords suite rows beyond retention
     * @param terminalDurableExecutions terminal durable control rows awaiting lifecycle cleanup
     * @param terminalWorkItems terminal work rows awaiting lifecycle cleanup
     */
    public record StorageSnapshot(
            long executionRecords,
            long expiredExecutionRecords,
            long suiteRecords,
            long expiredSuiteRecords,
            long terminalDurableExecutions,
            long terminalWorkItems) {
        /** Rejects negative or internally inconsistent aggregate counts. */
        public StorageSnapshot {
            if (executionRecords < 0 || expiredExecutionRecords < 0
                    || expiredExecutionRecords > executionRecords
                    || suiteRecords < 0 || expiredSuiteRecords < 0
                    || expiredSuiteRecords > suiteRecords
                    || terminalDurableExecutions < 0 || terminalWorkItems < 0) {
                throw new IllegalArgumentException("Invalid test-runtime storage aggregate");
            }
        }
    }

    private record RecordCounts(long total, long expired) {
    }
}
