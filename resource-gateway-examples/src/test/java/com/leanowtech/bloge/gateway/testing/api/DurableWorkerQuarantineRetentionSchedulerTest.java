package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableWorkerQuarantineRetentionSchedulerTest {

    @Test
    void delegatesExactPolicyAndKeepsLaterTicksAliveAfterBusyAndFailure() {
        DatabaseDurableWorkerQuarantineControlPlane controlPlane =
                mock(DatabaseDurableWorkerQuarantineControlPlane.class);
        DurableWorkerQuarantineRetentionTelemetry telemetry =
                mock(DurableWorkerQuarantineRetentionTelemetry.class);
        var busy = new DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt(
                DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.LEASE_BUSY, null);
        var completed = new DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt(
                DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.COMPLETED,
                new DatabaseDurableWorkerQuarantineControlPlane.RetentionResult(
                        4, 2, 3, Instant.parse("2026-07-17T06:00:00Z")));
        when(controlPlane.retainPage(Duration.ofDays(30), Duration.ofDays(365),
                Duration.ofDays(365), 100))
                .thenReturn(busy)
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(completed);
        when(controlPlane.retentionSnapshot()).thenReturn(snapshot());
        DurableWorkerQuarantineRetentionScheduler scheduler =
                new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                        Duration.ofDays(30), Duration.ofDays(365),
                        Duration.ofDays(365), 100, telemetry, Duration.ofHours(1));

        scheduler.retain();
        scheduler.retain();
        scheduler.retain();

        verify(controlPlane, times(3)).retainPage(Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(365), 100);
        verify(telemetry, times(2)).record(any(), any(Duration.class));
        verify(telemetry).recordFailure(any(Duration.class));
        verify(telemetry).refresh(any());
    }

    @Test
    void doesNotMisclassifyPostCommitTelemetryFailuresAsRetentionFailures() {
        DatabaseDurableWorkerQuarantineControlPlane controlPlane =
                mock(DatabaseDurableWorkerQuarantineControlPlane.class);
        DurableWorkerQuarantineRetentionTelemetry telemetry =
                mock(DurableWorkerQuarantineRetentionTelemetry.class);
        var completed = new DatabaseDurableWorkerQuarantineControlPlane.RetentionAttempt(
                DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.COMPLETED,
                new DatabaseDurableWorkerQuarantineControlPlane.RetentionResult(
                        1, 0, 0, Instant.parse("2026-07-17T06:00:00Z")));
        when(controlPlane.retainPage(Duration.ofDays(30), Duration.ofDays(365),
                Duration.ofDays(365), 100)).thenReturn(completed);
        doThrow(new IllegalStateException("metrics backend unavailable"))
                .when(telemetry).record(any(), any(Duration.class));
        when(controlPlane.retentionSnapshot())
                .thenThrow(new IllegalStateException("snapshot unavailable"));
        DurableWorkerQuarantineRetentionScheduler scheduler =
                new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                        Duration.ofDays(30), Duration.ofDays(365),
                        Duration.ofDays(365), 100, telemetry, Duration.ofHours(1));

        scheduler.retain();

        verify(controlPlane).retentionSnapshot();
        verify(telemetry, never()).recordFailure(any(Duration.class));
    }

    @Test
    void rejectsUnsafeOrUnboundedLifecyclePolicyAtAssemblyTime() {
        DatabaseDurableWorkerQuarantineControlPlane controlPlane =
                mock(DatabaseDurableWorkerQuarantineControlPlane.class);

        assertThatThrownBy(() -> new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                Duration.ofMinutes(59), Duration.ofDays(365), Duration.ofDays(365), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandRetention");
        assertThatThrownBy(() -> new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                Duration.ofDays(30), Duration.ofHours(23), Duration.ofDays(365), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("historyRetention");
        assertThatThrownBy(() -> new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofHours(23), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstoneRetention");
        assertThatThrownBy(() -> new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365), 1_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> new DurableWorkerQuarantineRetentionScheduler(controlPlane,
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365), 100,
                DurableWorkerQuarantineRetentionTelemetry.noop(), Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleInterval");
    }

    private static DatabaseDurableWorkerQuarantineControlPlane.RetentionSnapshot snapshot() {
        Instant now = Instant.parse("2026-07-17T06:00:00Z");
        return new DatabaseDurableWorkerQuarantineControlPlane.RetentionSnapshot(
                "", 1, now, 2, 4, 2, 3, 2, now, now);
    }
}
