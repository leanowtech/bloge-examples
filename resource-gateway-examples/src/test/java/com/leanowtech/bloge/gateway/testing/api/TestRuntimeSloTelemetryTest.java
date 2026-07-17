package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestRuntimeSloTelemetryTest {

    @Test
    void exportsOnlyClosedAggregateStatusQueueScopeAndStorageTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestRuntimeSloTelemetry telemetry = new TestRuntimeSloTelemetry(registry);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot =
                new DatabaseTestRuntimeSloControlPlane.OperationalSnapshot(
                        observedAt, Duration.ofMinutes(15),
                        counts(TestRunEvidence.Status.class,
                                TestRunEvidence.Status.PASSED, 7,
                                TestRunEvidence.Status.EVIDENCE_INCOMPLETE, 2),
                        counts(TestSuiteRunEvidence.Status.class,
                                TestSuiteRunEvidence.Status.PASSED, 3,
                                TestSuiteRunEvidence.Status.PARTIAL, 1),
                        counts(DurableTestExecutionCheckpoint.Status.class,
                                DurableTestExecutionCheckpoint.Status.SUSPENDED, 4),
                        counts(ExecutionStatus.class, ExecutionStatus.PAUSED, 5),
                        counts(WorkItemStatus.class, WorkItemStatus.READY, 6),
                        queue(2, 1, observedAt.minusSeconds(50)),
                        queue(3, 0, observedAt.minusSeconds(40)),
                        queue(4, 1, observedAt.minusSeconds(30)),
                        queue(5, 2, observedAt.minusSeconds(20)),
                        new DatabaseTestRuntimeSloControlPlane.WorkerCandidateDeferralSnapshot(
                                counts(DurableTestExecutionCheckpointRepository
                                                .WorkerCandidateDeferralReason.class,
                                        DurableTestExecutionCheckpointRepository
                                                .WorkerCandidateDeferralReason
                                                .AUTHORIZATION_DENIED, 3),
                                counts(DurableTestExecutionCheckpointRepository
                                                .WorkerCandidateDeferralReason.class,
                                        DurableTestExecutionCheckpointRepository
                                                .WorkerCandidateDeferralReason
                                                .AUTHORIZATION_DENIED, 2),
                                observedAt.minusSeconds(100), 5),
                        new DatabaseTestRuntimeSloControlPlane.WorkerCandidateQuarantineSnapshot(
                                counts(DurableTestExecutionCheckpointRepository
                                                .WorkerCandidateDeferralReason.class,
                                        DurableTestExecutionCheckpointRepository
                                                .WorkerCandidateDeferralReason
                                                .AUTHORIZATION_DENIED, 2),
                                observedAt.minusSeconds(200), 32,
                                counts(DatabaseDurableWorkerQuarantineControlPlane
                                                .QuarantineState.class,
                                        DatabaseDurableWorkerQuarantineControlPlane
                                                .QuarantineState.AVAILABLE, 1,
                                        DatabaseDurableWorkerQuarantineControlPlane
                                                .QuarantineState.CLAIMED, 1),
                                1, 7, 4, 2, 3),
                        new DatabaseTestRuntimeSloControlPlane.StorageSnapshot(
                                20, 2, 10, 1, 8, 9));

        telemetry.observe(snapshot, TestRuntimeSloMonitor.State.SLO_VIOLATED, 2222, 2500);

        assertThat(gauge(registry, "resource.gateway.test.runtime.execution.outcomes",
                "status", "passed")).isEqualTo(7.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.suite.outcomes",
                "status", "partial")).isEqualTo(1.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.durable.executions",
                "status", "suspended")).isEqualTo(4.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.engine.executions",
                "status", "paused")).isEqualTo(5.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.work.items",
                "status", "ready")).isEqualTo(6.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.queue.depth",
                "queue", "work_item")).isEqualTo(5.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.lease.expired",
                "queue", "suite_run")).isEqualTo(1.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.queue.oldest.age",
                "queue", "durable_creation")).isEqualTo(40.0);
        assertThat(gauge(registry,
                "resource.gateway.test.runtime.evidence.incomplete.basis_points",
                "scope", "execution")).isEqualTo(2222.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.storage.records",
                "kind", "execution")).isEqualTo(20.0);
        assertThat(gauge(registry, "resource.gateway.test.runtime.storage.backlog",
                "kind", "durable_terminal")).isEqualTo(8.0);
        assertThat(gauge(registry,
                "resource.gateway.test.runtime.worker.candidate.deferrals",
                "reason", "authorization_denied")).isEqualTo(3.0);
        assertThat(gauge(registry,
                "resource.gateway.test.runtime.worker.candidate.deferrals.active",
                "reason", "authorization_denied")).isEqualTo(2.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.deferrals.retry_due")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.deferrals.maximum_failures")
                .gauge().value()).isEqualTo(5.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.deferrals.oldest_age")
                .gauge().value()).isEqualTo(100.0);
        assertThat(gauge(registry,
                "resource.gateway.test.runtime.worker.candidate.quarantines",
                "reason", "authorization_denied")).isEqualTo(2.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.maximum_failures")
                .gauge().value()).isEqualTo(32.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.oldest_age")
                .gauge().value()).isEqualTo(200.0);
        assertThat(gauge(registry,
                "resource.gateway.test.runtime.worker.candidate.quarantines.maintenance",
                "state", "claimed")).isEqualTo(1.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.claims.expired")
                .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.history")
                .gauge().value()).isEqualTo(7.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.discard.approvals.live")
                .gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.discard.approvals.expired")
                .gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(
                "resource.gateway.test.runtime.worker.candidate.quarantines.discards.approved.history")
                .gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("resource.gateway.test.runtime.health").gauge().value())
                .isEqualTo(-1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey())
                                .isIn("status", "queue", "scope", "kind", "reason", "state")));

        telemetry.observeStoreUnavailable();
        assertThat(registry.get("resource.gateway.test.runtime.health").gauge().value())
                .isEqualTo(-2.0);
    }

    private static double gauge(
            SimpleMeterRegistry registry,
            String name,
            String tag,
            String value) {
        return registry.get(name).tag(tag, value).gauge().value();
    }

    private static DatabaseTestRuntimeSloControlPlane.QueueSnapshot queue(
            long depth, long expired, Instant oldest) {
        return new DatabaseTestRuntimeSloControlPlane.QueueSnapshot(depth, expired, oldest);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> Map<E, Long> counts(
            Class<E> type,
            Object... entries) {
        EnumMap<E, Long> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            result.put(value, 0L);
        }
        for (int index = 0; index < entries.length; index += 2) {
            result.put((E) entries[index], ((Number) entries[index + 1]).longValue());
        }
        return result;
    }
}
