package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
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
            StorageSnapshot storage) {
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
