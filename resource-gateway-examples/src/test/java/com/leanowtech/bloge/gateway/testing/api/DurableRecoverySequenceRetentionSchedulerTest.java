package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableRecoverySequenceRetentionSchedulerTest {

    @Test
    void delegatesExactPolicyAndKeepsLaterTicksAliveAfterBusyAndFailure() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry telemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        var busy = DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionAttempt.leaseBusy();
        var completed = completedAttempt();
        when(checkpoints.retainRecoverySequencePage(
                Duration.ofDays(30), Duration.ofDays(365), 100))
                .thenReturn(busy)
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(completed);
        when(checkpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenReturn(snapshot());
        var scheduler = new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofDays(30), Duration.ofDays(365), 100,
                telemetry, Duration.ofHours(1));

        scheduler.retain();
        scheduler.retain();
        scheduler.retain();

        verify(checkpoints, times(3)).retainRecoverySequencePage(
                Duration.ofDays(30), Duration.ofDays(365), 100);
        verify(telemetry, times(2)).record(any(), any(Duration.class));
        verify(telemetry).recordFailure(any(Duration.class));
        verify(telemetry).refresh(any());
    }

    @Test
    void doesNotMisclassifyPostCommitTelemetryFailuresAsRetentionFailures() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry telemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        when(checkpoints.retainRecoverySequencePage(
                Duration.ofDays(30), Duration.ofDays(365), 100))
                .thenReturn(completedAttempt());
        doThrow(new IllegalStateException("metrics backend unavailable"))
                .when(telemetry).record(any(), any(Duration.class));
        when(checkpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenThrow(new IllegalStateException("snapshot unavailable"));
        var scheduler = new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofDays(30), Duration.ofDays(365), 100,
                telemetry, Duration.ofHours(1));

        scheduler.retain();

        verify(checkpoints).recoverySequenceRetentionSnapshot(Duration.ofDays(30));
        verify(telemetry, never()).recordFailure(any(Duration.class));
    }

    @Test
    void rejectsUnsafeOrUnboundedLifecyclePolicyAtAssemblyTime() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);

        assertThatThrownBy(() -> new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofMinutes(59), Duration.ofDays(365), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandRetention");
        assertThatThrownBy(() -> new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofDays(30), Duration.ofHours(23), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstoneRetention");
        assertThatThrownBy(() -> new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofDays(30), Duration.ofDays(365), 1_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> new DurableRecoverySequenceRetentionScheduler(
                checkpoints, Duration.ofDays(30), Duration.ofDays(365), 100,
                DurableRecoverySequenceRetentionTelemetry.noop(),
                Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleInterval");
    }

    @Test
    void publishesOnlyClosedResultTagsAndAggregateLifecycleGauges() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new DurableRecoverySequenceRetentionTelemetry(registry);

        telemetry.record(completedAttempt(), Duration.ofMillis(12));
        telemetry.recordFailure(Duration.ofMillis(3));
        var snapshot = backloggedSnapshot();
        telemetry.observeSlo(
                snapshot, DurableRecoverySequenceRetentionSloMonitor.State.SLO_VIOLATED,
                Duration.ofMinutes(4), Duration.ofHours(2), Duration.ofHours(3));

        String prefix =
                "resource.gateway.test.runtime.durable.recovery.sequences.retention.";
        assertThat(registry.get(prefix + "attempts")
                .tag("result", "completed").counter().count()).isEqualTo(1);
        assertThat(registry.get(prefix + "attempts")
                .tag("result", "failed").counter().count()).isEqualTo(1);
        assertThat(registry.get(prefix + "sequences.tombstoned.total")
                .gauge().value()).isEqualTo(4);
        assertThat(registry.get(prefix + "heartbeats.purged.total")
                .gauge().value()).isEqualTo(6);
        assertThat(registry.get(prefix + "tombstones.records")
                .gauge().value()).isEqualTo(3);
        assertThat(registry.get(prefix + "sequences.overdue")
                .gauge().value()).isEqualTo(2);
        assertThat(registry.get(prefix + "tombstones.expired")
                .gauge().value()).isEqualTo(1);
        assertThat(registry.get(prefix + "last.success.age")
                .gauge().value()).isEqualTo(240);
        assertThat(registry.get(prefix + "health")
                .gauge().value()).isEqualTo(-1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isEqualTo("result")));
    }

    private static DurableTestExecutionCheckpointRepository
    .RecoverySequenceRetentionAttempt completedAttempt() {
        return DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionAttempt.completed(
                        new DurableTestExecutionCheckpointRepository
                                .RecoverySequenceRetentionResult(
                                4, 3, 2, 6, 1,
                                Instant.parse("2026-07-17T12:00:00Z")));
    }

    private static DurableTestExecutionCheckpointRepository
    .RecoverySequenceRetentionSnapshot snapshot() {
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        return new DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionSnapshot(
                "", 1, Instant.EPOCH, 2,
                4, 3, 2, 6, 1, 5, 3,
                0, 0, null, null, now, now);
    }

    private static DurableTestExecutionCheckpointRepository
    .RecoverySequenceRetentionSnapshot backloggedSnapshot() {
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        return new DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionSnapshot(
                "", 1, Instant.EPOCH, 2,
                4, 3, 2, 6, 1, 5, 3,
                2, 1, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(3)), now.minus(Duration.ofMinutes(4)), now);
    }
}
