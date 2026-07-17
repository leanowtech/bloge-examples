package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRuntimeSloMonitorTest {

    @Test
    void ignoresBusinessFailuresAndAppliesMinimumEvidenceSampleSize() {
        DatabaseTestRuntimeSloControlPlane controlPlane =
                mock(DatabaseTestRuntimeSloControlPlane.class);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        Map<TestRunEvidence.Status, Long> executions = executionOutcomes(
                TestRunEvidence.Status.ASSERTION_FAILED, 5,
                TestRunEvidence.Status.EXECUTION_FAILED, 3,
                TestRunEvidence.Status.TIMED_OUT, 1,
                TestRunEvidence.Status.EVIDENCE_INCOMPLETE, 1);
        Map<TestSuiteRunEvidence.Status, Long> suites = suiteOutcomes(
                TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES, 3);
        when(controlPlane.operationalSnapshot(Duration.ofMinutes(15)))
                .thenReturn(snapshot(observedAt, executions, suites,
                        queue(0, 0, null), queue(0, 0, null),
                        queue(0, 0, null), queue(0, 0, null), storage(0)));
        TestRuntimeSloMonitor monitor = new TestRuntimeSloMonitor(
                controlPlane, TestRuntimeSloTelemetry.noop(), policy(20, 5));

        monitor.refresh();

        Health health = monitor.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "HEALTHY")
                .containsEntry("executionSamples", 10L)
                .containsEntry("incompleteExecutions", 1L)
                .containsEntry("violations", java.util.List.of());
    }

    @Test
    void reportsEveryEvidenceQueueAndStorageSloViolationWithStableCodes() {
        DatabaseTestRuntimeSloControlPlane controlPlane =
                mock(DatabaseTestRuntimeSloControlPlane.class);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        Map<TestRunEvidence.Status, Long> executions = executionOutcomes(
                TestRunEvidence.Status.PASSED, 1,
                TestRunEvidence.Status.CONTROL_PLAN_UNAVAILABLE, 1);
        Map<TestSuiteRunEvidence.Status, Long> suites = suiteOutcomes(
                TestSuiteRunEvidence.Status.PASSED, 1,
                TestSuiteRunEvidence.Status.PARTIAL, 1);
        DatabaseTestRuntimeSloControlPlane.QueueSnapshot overloaded =
                queue(2, 1, observedAt.minusSeconds(120));
        when(controlPlane.operationalSnapshot(Duration.ofMinutes(15)))
                .thenReturn(snapshot(observedAt, executions, suites,
                        overloaded, overloaded, overloaded, overloaded, storage(1)));
        TestRuntimeSloMonitor monitor = new TestRuntimeSloMonitor(
                controlPlane, TestRuntimeSloTelemetry.noop(), policy(2, 2));

        monitor.refresh();

        Health health = monitor.health();
        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("state", "SLO_VIOLATED")
                .containsEntry("executionIncompleteBasisPoints", 5000)
                .containsEntry("suiteIncompleteBasisPoints", 5000);
        @SuppressWarnings("unchecked")
        java.util.List<String> violations =
                (java.util.List<String>) health.getDetails().get("violations");
        assertThat(violations)
                .containsExactly(
                        "EXECUTION_EVIDENCE_INCOMPLETE_RATE_EXCEEDED",
                        "SUITE_EVIDENCE_INCOMPLETE_RATE_EXCEEDED",
                        "SUITE_RUN_CAPACITY_EXCEEDED",
                        "SUITE_RUN_LEASE_BACKLOG",
                        "SUITE_RUN_STALE",
                        "DURABLE_CREATION_CAPACITY_EXCEEDED",
                        "DURABLE_CREATION_LEASE_BACKLOG",
                        "DURABLE_CREATION_STALE",
                        "DURABLE_EXECUTION_CAPACITY_EXCEEDED",
                        "DURABLE_EXECUTION_LEASE_BACKLOG",
                        "DURABLE_EXECUTION_STALE",
                        "WORK_ITEM_CAPACITY_EXCEEDED",
                        "WORK_ITEM_CLAIM_BACKLOG",
                        "WORK_ITEM_DISPATCH_STALE",
                        "EXECUTION_RETENTION_BACKLOG_EXCEEDED",
                        "SUITE_RETENTION_BACKLOG_EXCEEDED",
                        "DURABLE_TERMINAL_RETENTION_BACKLOG_EXCEEDED",
                        "WORK_ITEM_TERMINAL_RETENTION_BACKLOG_EXCEEDED");
        assertThat(health.getDetails().toString())
                .doesNotContain("tenant", "runId", "payload");
    }

    @Test
    void reportsStoreFailureWithoutLeakingExceptionDetails() {
        DatabaseTestRuntimeSloControlPlane controlPlane =
                mock(DatabaseTestRuntimeSloControlPlane.class);
        when(controlPlane.operationalSnapshot(Duration.ofMinutes(15)))
                .thenThrow(new IllegalStateException("password=do-not-leak"));
        TestRuntimeSloMonitor monitor = new TestRuntimeSloMonitor(
                controlPlane, TestRuntimeSloTelemetry.noop(), policy(2, 2));

        monitor.refresh();

        Health health = monitor.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("state", "STORE_UNAVAILABLE")
                .containsEntry("violations",
                        java.util.List.of("TEST_RUNTIME_STORE_UNAVAILABLE"));
        assertThat(health.getDetails().toString()).doesNotContain("password", "do-not-leak");
    }

    @Test
    void reportsWorkerCandidateBackoffPressureWithoutScopeIdentity() {
        DatabaseTestRuntimeSloControlPlane controlPlane =
                mock(DatabaseTestRuntimeSloControlPlane.class);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long> totals =
                zeroes(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.class);
        Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long> active =
                zeroes(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.class);
        totals.put(DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                .AUTHORIZATION_DENIED, 2L);
        totals.put(DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                .AUTHORIZATION_CONFLICT, 1L);
        active.put(DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                .AUTHORIZATION_DENIED, 2L);
        var deferrals = new DatabaseTestRuntimeSloControlPlane
                .WorkerCandidateDeferralSnapshot(
                totals, active, observedAt.minusSeconds(120), 7);
        when(controlPlane.operationalSnapshot(Duration.ofMinutes(15)))
                .thenReturn(snapshot(observedAt, executionOutcomes(), suiteOutcomes(),
                        queue(0, 0, null), queue(0, 0, null),
                        queue(0, 0, null), queue(0, 0, null), deferrals, storage(0)));
        TestRuntimeSloMonitor monitor = new TestRuntimeSloMonitor(
                controlPlane, TestRuntimeSloTelemetry.noop(), policy(2, 2));

        monitor.refresh();

        Health health = monitor.health();
        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        @SuppressWarnings("unchecked")
        java.util.List<String> violations =
                (java.util.List<String>) health.getDetails().get("violations");
        assertThat(violations).containsExactly(
                "WORKER_CANDIDATE_BACKOFF_CAPACITY_EXCEEDED",
                "WORKER_CANDIDATE_RETRY_DUE_BACKLOG",
                "WORKER_CANDIDATE_REPEATED_FAILURES",
                "WORKER_CANDIDATE_BACKOFF_STALE");
        assertThat(health.getDetails().toString())
                .contains("maximumConsecutiveFailures=7")
                .doesNotContain("tenant", "runId", "checkpointFingerprint");
    }

    @Test
    void reportsPermanentWorkerCandidateQuarantinePressureWithoutScopeIdentity() {
        DatabaseTestRuntimeSloControlPlane controlPlane =
                mock(DatabaseTestRuntimeSloControlPlane.class);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        Map<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason, Long> totals =
                zeroes(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.class);
        totals.put(DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                .AUTHORIZATION_DENIED, 2L);
        var quarantines = new DatabaseTestRuntimeSloControlPlane
                .WorkerCandidateQuarantineSnapshot(
                totals, observedAt.minusSeconds(120), 32,
                counts(DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.class,
                        DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.AVAILABLE, 1,
                        DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.CLAIMED, 1),
                1, 7, 1, 2, 3);
        when(controlPlane.operationalSnapshot(Duration.ofMinutes(15)))
                .thenReturn(snapshot(observedAt, executionOutcomes(), suiteOutcomes(),
                        queue(0, 0, null), queue(0, 0, null),
                        queue(0, 0, null), queue(0, 0, null),
                        DatabaseTestRuntimeSloControlPlane
                                .WorkerCandidateDeferralSnapshot.empty(),
                        quarantines, storage(0)));
        TestRuntimeSloMonitor monitor = new TestRuntimeSloMonitor(
                controlPlane, TestRuntimeSloTelemetry.noop(), policy(2, 2));

        monitor.refresh();

        Health health = monitor.health();
        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        @SuppressWarnings("unchecked")
        java.util.List<String> violations =
                (java.util.List<String>) health.getDetails().get("violations");
        assertThat(violations).containsExactly(
                "WORKER_CANDIDATE_QUARANTINE_BACKLOG",
                "WORKER_CANDIDATE_QUARANTINE_STALE",
                "WORKER_CANDIDATE_QUARANTINE_CLAIM_EXPIRED",
                "WORKER_CANDIDATE_QUARANTINE_DISCARD_APPROVAL_EXPIRED");
        assertThat(health.getDetails().toString())
                .contains("workerCandidateQuarantines={")
                .contains("available=1")
                .contains("claimed=1")
                .contains("expiredClaims=1")
                .contains("liveDiscardApprovals=1")
                .contains("expiredDiscardApprovals=2")
                .contains("historyRecords=7")
                .contains("approvedDiscardHistoryRecords=3")
                .contains("maximumConsecutiveFailures=32")
                .doesNotContain("tenant", "runId", "checkpointFingerprint");
    }

    @Test
    void rejectsInvalidSloPoliciesBeforeTheMonitorCanStart() {
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.EvidencePolicy(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.EvidencePolicy(1, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.QueuePolicy(-1, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.StoragePolicy(0, 0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.WorkerCandidateDeferralPolicy(
                -1, 0, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.WorkerCandidateQuarantinePolicy(
                -1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.WorkerCandidateQuarantinePolicy(
                0, Duration.ofSeconds(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.WorkerCandidateQuarantinePolicy(
                0, Duration.ofSeconds(1), 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestRuntimeSloMonitor.Policy(
                Duration.ofDays(366),
                new TestRuntimeSloMonitor.EvidencePolicy(1, 0),
                new TestRuntimeSloMonitor.EvidencePolicy(1, 0),
                new TestRuntimeSloMonitor.QueuePolicy(1, 0, Duration.ofSeconds(1)),
                new TestRuntimeSloMonitor.QueuePolicy(1, 0, Duration.ofSeconds(1)),
                new TestRuntimeSloMonitor.QueuePolicy(1, 0, Duration.ofSeconds(1)),
                new TestRuntimeSloMonitor.QueuePolicy(1, 0, Duration.ofSeconds(1)),
                new TestRuntimeSloMonitor.StoragePolicy(0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365 days");
    }

    private static TestRuntimeSloMonitor.Policy policy(
            long executionMinimumSamples,
            long suiteMinimumSamples) {
        TestRuntimeSloMonitor.QueuePolicy queue =
                new TestRuntimeSloMonitor.QueuePolicy(1, 0, Duration.ofSeconds(60));
        return new TestRuntimeSloMonitor.Policy(
                Duration.ofMinutes(15),
                new TestRuntimeSloMonitor.EvidencePolicy(executionMinimumSamples, 0),
                new TestRuntimeSloMonitor.EvidencePolicy(suiteMinimumSamples, 0),
                queue, queue, queue, queue,
                new TestRuntimeSloMonitor.WorkerCandidateDeferralPolicy(
                        1, 0, 2, Duration.ofSeconds(60)),
                new TestRuntimeSloMonitor.WorkerCandidateQuarantinePolicy(
                        1, Duration.ofSeconds(60), 0, 0),
                new TestRuntimeSloMonitor.StoragePolicy(0, 0, 0, 0));
    }

    private static DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot(
            Instant observedAt,
            Map<TestRunEvidence.Status, Long> executions,
            Map<TestSuiteRunEvidence.Status, Long> suites,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot suiteQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot creationQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot durableQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot workQueue,
            DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage) {
        return snapshot(observedAt, executions, suites, suiteQueue, creationQueue,
                durableQueue, workQueue,
                DatabaseTestRuntimeSloControlPlane.WorkerCandidateDeferralSnapshot.empty(),
                DatabaseTestRuntimeSloControlPlane.WorkerCandidateQuarantineSnapshot.empty(),
                storage);
    }

    private static DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot(
            Instant observedAt,
            Map<TestRunEvidence.Status, Long> executions,
            Map<TestSuiteRunEvidence.Status, Long> suites,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot suiteQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot creationQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot durableQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot workQueue,
            DatabaseTestRuntimeSloControlPlane.WorkerCandidateDeferralSnapshot deferrals,
            DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage) {
        return snapshot(observedAt, executions, suites, suiteQueue, creationQueue,
                durableQueue, workQueue, deferrals,
                DatabaseTestRuntimeSloControlPlane.WorkerCandidateQuarantineSnapshot.empty(),
                storage);
    }

    private static DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot(
            Instant observedAt,
            Map<TestRunEvidence.Status, Long> executions,
            Map<TestSuiteRunEvidence.Status, Long> suites,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot suiteQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot creationQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot durableQueue,
            DatabaseTestRuntimeSloControlPlane.QueueSnapshot workQueue,
            DatabaseTestRuntimeSloControlPlane.WorkerCandidateDeferralSnapshot deferrals,
            DatabaseTestRuntimeSloControlPlane.WorkerCandidateQuarantineSnapshot quarantines,
            DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage) {
        return new DatabaseTestRuntimeSloControlPlane.OperationalSnapshot(
                observedAt, Duration.ofMinutes(15), executions, suites,
                zeroes(DurableTestExecutionCheckpoint.Status.class),
                zeroes(ExecutionStatus.class), zeroes(WorkItemStatus.class),
                suiteQueue, creationQueue, durableQueue, workQueue, deferrals, quarantines,
                storage);
    }

    private static DatabaseTestRuntimeSloControlPlane.QueueSnapshot queue(
            long depth, long expiredClaims, Instant oldest) {
        return new DatabaseTestRuntimeSloControlPlane.QueueSnapshot(
                depth, expiredClaims, oldest);
    }

    private static DatabaseTestRuntimeSloControlPlane.StorageSnapshot storage(long value) {
        return new DatabaseTestRuntimeSloControlPlane.StorageSnapshot(
                value, value, value, value, value, value);
    }

    private static Map<TestRunEvidence.Status, Long> executionOutcomes(
            Object... entries) {
        EnumMap<TestRunEvidence.Status, Long> values =
                new EnumMap<>(TestRunEvidence.Status.class);
        for (int index = 0; index < entries.length; index += 2) {
            values.put((TestRunEvidence.Status) entries[index],
                    ((Number) entries[index + 1]).longValue());
        }
        return values;
    }

    private static Map<TestSuiteRunEvidence.Status, Long> suiteOutcomes(
            Object... entries) {
        EnumMap<TestSuiteRunEvidence.Status, Long> values =
                new EnumMap<>(TestSuiteRunEvidence.Status.class);
        for (int index = 0; index < entries.length; index += 2) {
            values.put((TestSuiteRunEvidence.Status) entries[index],
                    ((Number) entries[index + 1]).longValue());
        }
        return values;
    }

    private static <E extends Enum<E>> Map<E, Long> zeroes(Class<E> type) {
        EnumMap<E, Long> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            result.put(value, 0L);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> Map<E, Long> counts(
            Class<E> type, Object... entries) {
        Map<E, Long> result = zeroes(type);
        for (int index = 0; index < entries.length; index += 2) {
            result.put((E) entries[index], ((Number) entries[index + 1]).longValue());
        }
        return result;
    }
}
