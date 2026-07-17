package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-closed SLO assessor for execution evidence, control queues, and storage capacity.
 *
 * <p>Business assertion failures, expected negative cases, and product-under-test failures are
 * deliberately not deployment-health failures. The monitor assesses only whether the testing
 * platform produced complete evidence and whether its ownership, queue, and retention control
 * surfaces remain inside bounded operational policy.</p>
 */
public final class TestRuntimeSloMonitor implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(TestRuntimeSloMonitor.class);

    private final DatabaseTestRuntimeSloControlPlane controlPlane;
    private final TestRuntimeSloTelemetry telemetry;
    private final Policy policy;
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * Creates a profile-gated global test-runtime SLO monitor.
     *
     * @param controlPlane transactionally consistent aggregate observation authority
     * @param telemetry fixed-vocabulary metric adapter
     * @param policy evidence, queue, and storage limits
     */
    public TestRuntimeSloMonitor(
            DatabaseTestRuntimeSloControlPlane controlPlane,
            TestRuntimeSloTelemetry telemetry,
            Policy policy) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Refreshes health and metrics from one database-clock operational snapshot. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.runtime-slo.observation-interval-ms:30000}")
    public void refresh() {
        try {
            DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot =
                    controlPlane.operationalSnapshot(policy.outcomeLookback());
            Assessment assessment = assess(snapshot);
            latest.set(assessment);
            telemetry.observe(snapshot, assessment.state(),
                    assessment.executionIncompleteBasisPoints(),
                    assessment.suiteIncompleteBasisPoints());
        } catch (RuntimeException unavailable) {
            latest.set(Assessment.storeUnavailable());
            telemetry.observeStoreUnavailable();
            log.warn("Test-runtime SLO observation failed; health is fail-closed until "
                    + "a database snapshot succeeds");
        }
    }

    /** Returns the latest aggregate health state, observing once on first access. */
    @Override
    public Health health() {
        Assessment assessment = latest.get();
        if (assessment == null) {
            refresh();
            assessment = latest.get();
        }
        Health.Builder builder = Health.status(status(assessment.state()))
                .withDetail("state", assessment.state().name())
                .withDetail("violations", assessment.violations());
        if (assessment.observedAt() != null) {
            builder.withDetail("observedAt", assessment.observedAt().toString())
                    .withDetail("outcomeLookbackSeconds", policy.outcomeLookback().toSeconds())
                    .withDetail("executionSamples", assessment.executionSamples())
                    .withDetail("incompleteExecutions", assessment.incompleteExecutions())
                    .withDetail("executionIncompleteBasisPoints",
                            assessment.executionIncompleteBasisPoints())
                    .withDetail("suiteSamples", assessment.suiteSamples())
                    .withDetail("incompleteSuites", assessment.incompleteSuites())
                    .withDetail("suiteIncompleteBasisPoints",
                            assessment.suiteIncompleteBasisPoints())
                    .withDetail("suiteRunQueue", queueDetails(assessment.suiteRuns()))
                    .withDetail("durableCreationQueue",
                            queueDetails(assessment.durableCreations()))
                    .withDetail("durableExecutionQueue",
                            queueDetails(assessment.durableExecutions()))
                    .withDetail("workItemQueue", queueDetails(assessment.workItems()))
                    .withDetail("workerCandidateDeferrals",
                            workerDeferralDetails(assessment.workerCandidateDeferrals()))
                    .withDetail("workerCandidateQuarantines",
                            workerQuarantineDetails(assessment.workerCandidateQuarantines()))
                    .withDetail("storage", assessment.storage());
        }
        return builder.build();
    }

    private Assessment assess(
            DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot) {
        List<String> violations = new ArrayList<>();
        int executionIncompleteBasisPoints = basisPoints(
                snapshot.incompleteExecutions(), snapshot.executionSamples());
        int suiteIncompleteBasisPoints = basisPoints(
                snapshot.incompleteSuites(), snapshot.suiteSamples());
        assessEvidence(snapshot.executionSamples(), executionIncompleteBasisPoints,
                policy.executionEvidence(),
                Violation.EXECUTION_EVIDENCE_INCOMPLETE_RATE_EXCEEDED, violations);
        assessEvidence(snapshot.suiteSamples(), suiteIncompleteBasisPoints,
                policy.suiteEvidence(),
                Violation.SUITE_EVIDENCE_INCOMPLETE_RATE_EXCEEDED, violations);
        assessQueue(snapshot.suiteRuns(), policy.suiteRuns(),
                Violation.SUITE_RUN_CAPACITY_EXCEEDED,
                Violation.SUITE_RUN_LEASE_BACKLOG,
                Violation.SUITE_RUN_STALE, snapshot.observedAt(), violations);
        assessQueue(snapshot.durableCreations(), policy.durableCreations(),
                Violation.DURABLE_CREATION_CAPACITY_EXCEEDED,
                Violation.DURABLE_CREATION_LEASE_BACKLOG,
                Violation.DURABLE_CREATION_STALE, snapshot.observedAt(), violations);
        assessQueue(snapshot.durableExecutions(), policy.durableExecutions(),
                Violation.DURABLE_EXECUTION_CAPACITY_EXCEEDED,
                Violation.DURABLE_EXECUTION_LEASE_BACKLOG,
                Violation.DURABLE_EXECUTION_STALE, snapshot.observedAt(), violations);
        assessQueue(snapshot.workItems(), policy.workItems(),
                Violation.WORK_ITEM_CAPACITY_EXCEEDED,
                Violation.WORK_ITEM_CLAIM_BACKLOG,
                Violation.WORK_ITEM_DISPATCH_STALE, snapshot.observedAt(), violations);
        DatabaseTestRuntimeSloControlPlane.WorkerCandidateDeferralSnapshot deferrals =
                snapshot.workerCandidateDeferrals();
        if (deferrals.activeRecords() > policy.workerCandidateDeferrals().maxActiveRecords()) {
            violations.add(Violation.WORKER_CANDIDATE_BACKOFF_CAPACITY_EXCEEDED.name());
        }
        if (deferrals.retryDueRecords()
                > policy.workerCandidateDeferrals().maxRetryDueRecords()) {
            violations.add(Violation.WORKER_CANDIDATE_RETRY_DUE_BACKLOG.name());
        }
        if (deferrals.maximumActiveConsecutiveFailures()
                > policy.workerCandidateDeferrals().maxConsecutiveFailures()) {
            violations.add(Violation.WORKER_CANDIDATE_REPEATED_FAILURES.name());
        }
        Duration oldestDeferralAge = age(
                deferrals.oldestActiveObservedAt(), snapshot.observedAt());
        if (oldestDeferralAge != null && oldestDeferralAge.compareTo(
                policy.workerCandidateDeferrals().maxOldestActiveAge()) > 0) {
            violations.add(Violation.WORKER_CANDIDATE_BACKOFF_STALE.name());
        }
        DatabaseTestRuntimeSloControlPlane.WorkerCandidateQuarantineSnapshot quarantines =
                snapshot.workerCandidateQuarantines();
        if (quarantines.totalRecords()
                > policy.workerCandidateQuarantines().maxRecords()) {
            violations.add(Violation.WORKER_CANDIDATE_QUARANTINE_BACKLOG.name());
        }
        Duration oldestQuarantineAge = age(
                quarantines.oldestQuarantinedAt(), snapshot.observedAt());
        if (oldestQuarantineAge != null && oldestQuarantineAge.compareTo(
                policy.workerCandidateQuarantines().maxOldestAge()) > 0) {
            violations.add(Violation.WORKER_CANDIDATE_QUARANTINE_STALE.name());
        }
        DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage = snapshot.storage();
        if (storage.expiredExecutionRecords()
                > policy.storage().maxExpiredExecutionRecords()) {
            violations.add(Violation.EXECUTION_RETENTION_BACKLOG_EXCEEDED.name());
        }
        if (storage.expiredSuiteRecords() > policy.storage().maxExpiredSuiteRecords()) {
            violations.add(Violation.SUITE_RETENTION_BACKLOG_EXCEEDED.name());
        }
        if (storage.terminalDurableExecutions()
                > policy.storage().maxTerminalDurableExecutions()) {
            violations.add(Violation.DURABLE_TERMINAL_RETENTION_BACKLOG_EXCEEDED.name());
        }
        if (storage.terminalWorkItems() > policy.storage().maxTerminalWorkItems()) {
            violations.add(Violation.WORK_ITEM_TERMINAL_RETENTION_BACKLOG_EXCEEDED.name());
        }
        State state = violations.isEmpty() ? State.HEALTHY : State.SLO_VIOLATED;
        return new Assessment(state, List.copyOf(violations), snapshot.observedAt(),
                snapshot.executionSamples(), snapshot.incompleteExecutions(),
                executionIncompleteBasisPoints, snapshot.suiteSamples(),
                snapshot.incompleteSuites(), suiteIncompleteBasisPoints,
                observedQueue(snapshot.suiteRuns(), snapshot.observedAt()),
                observedQueue(snapshot.durableCreations(), snapshot.observedAt()),
                observedQueue(snapshot.durableExecutions(), snapshot.observedAt()),
                observedQueue(snapshot.workItems(), snapshot.observedAt()),
                new ObservedWorkerDeferrals(
                        deferrals.totalRecords(), deferrals.activeRecords(),
                        deferrals.retryDueRecords(),
                        deferrals.maximumActiveConsecutiveFailures(),
                        secondsOrUnknown(oldestDeferralAge)),
                new ObservedWorkerQuarantines(
                        quarantines.totalRecords(),
                        quarantines.maximumConsecutiveFailures(),
                        secondsOrUnknown(oldestQuarantineAge)), storage);
    }

    private static void assessEvidence(
            long samples,
            int incompleteBasisPoints,
            EvidencePolicy policy,
            Violation violation,
            List<String> violations) {
        if (samples >= policy.minimumSamples()
                && incompleteBasisPoints > policy.maxIncompleteBasisPoints()) {
            violations.add(violation.name());
        }
    }

    private static void assessQueue(
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot queue,
            QueuePolicy policy,
            Violation capacityViolation,
            Violation claimViolation,
            Violation staleViolation,
            Instant observedAt,
            List<String> violations) {
        if (queue.depth() > policy.maxDepth()) {
            violations.add(capacityViolation.name());
        }
        if (queue.expiredClaims() > policy.maxExpiredClaims()) {
            violations.add(claimViolation.name());
        }
        Duration age = age(queue.oldestActivityAt(), observedAt);
        if (age != null && age.compareTo(policy.maxOldestAge()) > 0) {
            violations.add(staleViolation.name());
        }
    }

    private static ObservedQueue observedQueue(
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot queue,
            Instant observedAt) {
        return new ObservedQueue(
                queue.depth(), queue.expiredClaims(), secondsOrUnknown(
                age(queue.oldestActivityAt(), observedAt)));
    }

    private static java.util.Map<String, Long> queueDetails(ObservedQueue queue) {
        return java.util.Map.of(
                "depth", queue.depth(),
                "expiredClaims", queue.expiredClaims(),
                "oldestAgeSeconds", queue.oldestAgeSeconds());
    }

    private static java.util.Map<String, Long> workerDeferralDetails(
            ObservedWorkerDeferrals deferrals) {
        return java.util.Map.of(
                "records", deferrals.records(),
                "active", deferrals.active(),
                "retryDue", deferrals.retryDue(),
                "maximumConsecutiveFailures", deferrals.maximumConsecutiveFailures(),
                "oldestActiveAgeSeconds", deferrals.oldestActiveAgeSeconds());
    }

    private static java.util.Map<String, Long> workerQuarantineDetails(
            ObservedWorkerQuarantines quarantines) {
        return java.util.Map.of(
                "records", quarantines.records(),
                "maximumConsecutiveFailures", quarantines.maximumConsecutiveFailures(),
                "oldestAgeSeconds", quarantines.oldestAgeSeconds());
    }

    private static Duration age(Instant earlier, Instant observedAt) {
        if (earlier == null) {
            return null;
        }
        Duration value = Duration.between(earlier, observedAt);
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static long secondsOrUnknown(Duration value) {
        return value == null ? -1 : value.toSeconds();
    }

    private static int basisPoints(long numerator, long denominator) {
        if (denominator == 0) {
            return 0;
        }
        return (int) Math.min(10_000,
                Math.round((double) numerator * 10_000.0 / denominator));
    }

    private static Status status(State state) {
        return switch (state) {
            case HEALTHY -> Status.UP;
            case SLO_VIOLATED -> Status.OUT_OF_SERVICE;
            case STORE_UNAVAILABLE -> Status.DOWN;
        };
    }

    /** Stable global test-runtime health states. */
    public enum State {
        /** Evidence completeness, queues, ownership, and storage satisfy policy. */
        HEALTHY,
        /** One or more stable platform SLO policies are violated. */
        SLO_VIOLATED,
        /** The isolated test-runtime store could not produce an aggregate snapshot. */
        STORE_UNAVAILABLE
    }

    /** Stable machine-readable global test-runtime policy failures. */
    public enum Violation {
        /** Recent child evidence incompleteness exceeds policy after minimum sample size. */
        EXECUTION_EVIDENCE_INCOMPLETE_RATE_EXCEEDED,
        /** Recent aggregate suite incompleteness exceeds policy after minimum sample size. */
        SUITE_EVIDENCE_INCOMPLETE_RATE_EXCEEDED,
        /** Active suite runs exceed bounded capacity. */
        SUITE_RUN_CAPACITY_EXCEEDED,
        /** Too many active suite ownership leases have expired. */
        SUITE_RUN_LEASE_BACKLOG,
        /** The oldest running suite checkpoint exceeds its activity-age policy. */
        SUITE_RUN_STALE,
        /** Pending durable creations exceed bounded capacity. */
        DURABLE_CREATION_CAPACITY_EXCEEDED,
        /** Too many pending durable-creation leases have expired. */
        DURABLE_CREATION_LEASE_BACKLOG,
        /** The oldest pending durable creation exceeds its activity-age policy. */
        DURABLE_CREATION_STALE,
        /** Resumable durable executions exceed bounded capacity. */
        DURABLE_EXECUTION_CAPACITY_EXCEEDED,
        /** Too many active or recovering durable execution leases have expired. */
        DURABLE_EXECUTION_LEASE_BACKLOG,
        /** The oldest active or recovering durable execution exceeds activity policy. */
        DURABLE_EXECUTION_STALE,
        /** Dispatchable and expired-claim work exceeds bounded capacity. */
        WORK_ITEM_CAPACITY_EXCEEDED,
        /** Too many worker claims have expired without recovery. */
        WORK_ITEM_CLAIM_BACKLOG,
        /** The oldest dispatchable work item exceeds queue-age policy. */
        WORK_ITEM_DISPATCH_STALE,
        /** Active deterministic worker-candidate backoffs exceed bounded capacity. */
        WORKER_CANDIDATE_BACKOFF_CAPACITY_EXCEEDED,
        /** Too many retry-due candidate records await another cyclic scan. */
        WORKER_CANDIDATE_RETRY_DUE_BACKLOG,
        /** A candidate has exceeded the accepted consecutive deterministic-failure count. */
        WORKER_CANDIDATE_REPEATED_FAILURES,
        /** The oldest active deterministic candidate backoff exceeds policy. */
        WORKER_CANDIDATE_BACKOFF_STALE,
        /** Active exact-checkpoint quarantines exceed their operational backlog limit. */
        WORKER_CANDIDATE_QUARANTINE_BACKLOG,
        /** The oldest unresolved exact-checkpoint quarantine exceeds policy. */
        WORKER_CANDIDATE_QUARANTINE_STALE,
        /** Expired child execution records exceed their retention backlog limit. */
        EXECUTION_RETENTION_BACKLOG_EXCEEDED,
        /** Expired suite records exceed their retention backlog limit. */
        SUITE_RETENTION_BACKLOG_EXCEEDED,
        /** Terminal durable checkpoints exceed their cleanup backlog limit. */
        DURABLE_TERMINAL_RETENTION_BACKLOG_EXCEEDED,
        /** Terminal BLOGE work items exceed their cleanup backlog limit. */
        WORK_ITEM_TERMINAL_RETENTION_BACKLOG_EXCEEDED,
        /** The isolated test-runtime store could not be observed. */
        TEST_RUNTIME_STORE_UNAVAILABLE
    }

    /**
     * Global policy split by evidence, queue, and storage semantics.
     *
     * @param outcomeLookback recent terminal-outcome observation window
     * @param executionEvidence child execution evidence-completeness policy
     * @param suiteEvidence aggregate suite evidence-completeness policy
     * @param suiteRuns running suite capacity, lease, and activity limits
     * @param durableCreations pending durable-creation limits
     * @param durableExecutions resumable durable-execution limits
     * @param workItems dispatchable and expired-claim work limits
     * @param workerCandidateDeferrals deterministic worker-candidate backoff limits
     * @param workerCandidateQuarantines permanent exact-checkpoint quarantine limits
     * @param storage retained-record cleanup backlog limits
     */
    public record Policy(
            Duration outcomeLookback,
            EvidencePolicy executionEvidence,
            EvidencePolicy suiteEvidence,
            QueuePolicy suiteRuns,
            QueuePolicy durableCreations,
            QueuePolicy durableExecutions,
            QueuePolicy workItems,
            WorkerCandidateDeferralPolicy workerCandidateDeferrals,
            WorkerCandidateQuarantinePolicy workerCandidateQuarantines,
            StoragePolicy storage) {
        /** Creates a compatibility policy with effectively unbounded candidate deferral limits. */
        public Policy(
                Duration outcomeLookback,
                EvidencePolicy executionEvidence,
                EvidencePolicy suiteEvidence,
                QueuePolicy suiteRuns,
                QueuePolicy durableCreations,
                QueuePolicy durableExecutions,
                QueuePolicy workItems,
                StoragePolicy storage) {
            this(outcomeLookback, executionEvidence, suiteEvidence, suiteRuns,
                    durableCreations, durableExecutions, workItems,
                    new WorkerCandidateDeferralPolicy(
                            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                            Duration.ofDays(36500)),
                    new WorkerCandidateQuarantinePolicy(
                            Long.MAX_VALUE, Duration.ofDays(36500)),
                    storage);
        }

        /** Creates a compatibility policy with effectively unbounded quarantine limits. */
        public Policy(
                Duration outcomeLookback,
                EvidencePolicy executionEvidence,
                EvidencePolicy suiteEvidence,
                QueuePolicy suiteRuns,
                QueuePolicy durableCreations,
                QueuePolicy durableExecutions,
                QueuePolicy workItems,
                WorkerCandidateDeferralPolicy workerCandidateDeferrals,
                StoragePolicy storage) {
            this(outcomeLookback, executionEvidence, suiteEvidence, suiteRuns,
                    durableCreations, durableExecutions, workItems, workerCandidateDeferrals,
                    new WorkerCandidateQuarantinePolicy(
                            Long.MAX_VALUE, Duration.ofDays(36500)),
                    storage);
        }

        /** Rejects missing or non-positive policy components. */
        public Policy {
            positive(outcomeLookback, "outcomeLookback");
            if (outcomeLookback.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException(
                        "outcomeLookback must not exceed 365 days");
            }
            Objects.requireNonNull(executionEvidence, "executionEvidence");
            Objects.requireNonNull(suiteEvidence, "suiteEvidence");
            Objects.requireNonNull(suiteRuns, "suiteRuns");
            Objects.requireNonNull(durableCreations, "durableCreations");
            Objects.requireNonNull(durableExecutions, "durableExecutions");
            Objects.requireNonNull(workItems, "workItems");
            Objects.requireNonNull(workerCandidateDeferrals, "workerCandidateDeferrals");
            Objects.requireNonNull(workerCandidateQuarantines, "workerCandidateQuarantines");
            Objects.requireNonNull(storage, "storage");
        }
    }

    /**
     * Evidence-completeness ratio policy.
     *
     * @param minimumSamples minimum window sample size before the ratio can fail health
     * @param maxIncompleteBasisPoints maximum accepted incomplete ratio from 0 to 10,000
     */
    public record EvidencePolicy(long minimumSamples, int maxIncompleteBasisPoints) {
        /** Validates bounded sample and ratio values. */
        public EvidencePolicy {
            if (minimumSamples <= 0 || maxIncompleteBasisPoints < 0
                    || maxIncompleteBasisPoints > 10_000) {
                throw new IllegalArgumentException("Invalid test-runtime evidence SLO policy");
            }
        }
    }

    /**
     * Queue capacity and ownership policy.
     *
     * @param maxDepth largest acceptable queue depth
     * @param maxExpiredClaims largest acceptable expired ownership backlog
     * @param maxOldestAge oldest acceptable actionable activity age
     */
    public record QueuePolicy(long maxDepth, long maxExpiredClaims, Duration maxOldestAge) {
        /** Validates non-negative counts and a positive age. */
        public QueuePolicy {
            if (maxDepth < 0 || maxExpiredClaims < 0) {
                throw new IllegalArgumentException("Test-runtime queue limits must be non-negative");
            }
            positive(maxOldestAge, "maxOldestAge");
        }
    }

    /**
     * Deterministic worker-candidate backoff pressure policy.
     *
     * @param maxActiveRecords largest acceptable active backoff population
     * @param maxRetryDueRecords largest acceptable due-but-not-yet-revisited population
     * @param maxConsecutiveFailures largest accepted active same-reason failure count
     * @param maxOldestActiveAge oldest accepted active backoff age
     */
    public record WorkerCandidateDeferralPolicy(
            long maxActiveRecords,
            long maxRetryDueRecords,
            long maxConsecutiveFailures,
            Duration maxOldestActiveAge) {
        /** Rejects negative counts or an unbounded/non-positive age. */
        public WorkerCandidateDeferralPolicy {
            if (maxActiveRecords < 0 || maxRetryDueRecords < 0
                    || maxConsecutiveFailures < 0) {
                throw new IllegalArgumentException(
                        "Worker candidate deferral SLO limits must be non-negative");
            }
            positive(maxOldestActiveAge, "maxOldestActiveAge");
        }
    }

    /**
     * Permanent exact-checkpoint quarantine pressure policy.
     *
     * @param maxRecords largest acceptable active quarantine backlog
     * @param maxOldestAge oldest acceptable unresolved quarantine age
     */
    public record WorkerCandidateQuarantinePolicy(long maxRecords, Duration maxOldestAge) {
        /** Rejects negative counts or a non-positive age. */
        public WorkerCandidateQuarantinePolicy {
            if (maxRecords < 0) {
                throw new IllegalArgumentException(
                        "Worker candidate quarantine SLO limit must be non-negative");
            }
            positive(maxOldestAge, "maxOldestAge");
        }
    }

    /**
     * Retained-record cleanup backlog policy.
     *
     * @param maxExpiredExecutionRecords largest expired child-evidence backlog
     * @param maxExpiredSuiteRecords largest expired suite-evidence backlog
     * @param maxTerminalDurableExecutions largest terminal durable-checkpoint backlog
     * @param maxTerminalWorkItems largest terminal work-item backlog
     */
    public record StoragePolicy(
            long maxExpiredExecutionRecords,
            long maxExpiredSuiteRecords,
            long maxTerminalDurableExecutions,
            long maxTerminalWorkItems) {
        /** Rejects negative storage backlog limits. */
        public StoragePolicy {
            if (maxExpiredExecutionRecords < 0 || maxExpiredSuiteRecords < 0
                    || maxTerminalDurableExecutions < 0 || maxTerminalWorkItems < 0) {
                throw new IllegalArgumentException(
                        "Test-runtime storage limits must be non-negative");
            }
        }
    }

    private record ObservedQueue(long depth, long expiredClaims, long oldestAgeSeconds) {
    }

    private record ObservedWorkerDeferrals(
            long records,
            long active,
            long retryDue,
            long maximumConsecutiveFailures,
            long oldestActiveAgeSeconds) {
    }

    private record ObservedWorkerQuarantines(
            long records,
            long maximumConsecutiveFailures,
            long oldestAgeSeconds) {
    }

    private record Assessment(
            State state,
            List<String> violations,
            Instant observedAt,
            long executionSamples,
            long incompleteExecutions,
            int executionIncompleteBasisPoints,
            long suiteSamples,
            long incompleteSuites,
            int suiteIncompleteBasisPoints,
            ObservedQueue suiteRuns,
            ObservedQueue durableCreations,
            ObservedQueue durableExecutions,
            ObservedQueue workItems,
            ObservedWorkerDeferrals workerCandidateDeferrals,
            ObservedWorkerQuarantines workerCandidateQuarantines,
            DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage) {
        private static Assessment storeUnavailable() {
            DatabaseTestRuntimeSloControlPlane.StorageSnapshot empty =
                    new DatabaseTestRuntimeSloControlPlane.StorageSnapshot(0, 0, 0, 0, 0, 0);
            ObservedQueue queue = new ObservedQueue(0, 0, -1);
            ObservedWorkerDeferrals deferrals =
                    new ObservedWorkerDeferrals(0, 0, 0, 0, -1);
            ObservedWorkerQuarantines quarantines =
                    new ObservedWorkerQuarantines(0, 0, -1);
            return new Assessment(State.STORE_UNAVAILABLE,
                    List.of(Violation.TEST_RUNTIME_STORE_UNAVAILABLE.name()), null,
                    0, 0, 0, 0, 0, 0, queue, queue, queue, queue,
                    deferrals, quarantines, empty);
        }
    }

    private static Duration positive(Duration value, String name) {
        Duration result = Objects.requireNonNull(value, name);
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }
}
