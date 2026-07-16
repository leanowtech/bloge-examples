package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableStateProjectionFindingRetentionSchedulerTest {

    @Test
    void delegatesBoundedRetentionAndSurvivesBusyAndFailedTicks() {
        DatabaseDurableStateProjectionControlPlane controlPlane =
                mock(DatabaseDurableStateProjectionControlPlane.class);
        DurableStateProjectionTelemetry telemetry = mock(DurableStateProjectionTelemetry.class);
        when(controlPlane.retainFindings(
                Duration.ofDays(30), Duration.ofDays(365), 1000))
                .thenReturn(DatabaseDurableStateProjectionControlPlane.RetentionAttempt.busy())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(DatabaseDurableStateProjectionControlPlane.RetentionAttempt.completed(
                        new DatabaseDurableStateProjectionControlPlane.RetentionResult(
                                2, 1, java.time.Instant.parse("2026-07-17T06:00:00Z"))));
        DurableStateProjectionFindingRetentionScheduler scheduler =
                new DurableStateProjectionFindingRetentionScheduler(
                        controlPlane, Duration.ofDays(30), Duration.ofDays(365), 5000,
                        telemetry);

        scheduler.retain();
        scheduler.retain();
        scheduler.retain();

        verify(controlPlane, times(3)).retainFindings(
                Duration.ofDays(30), Duration.ofDays(365), 1000);
        verify(telemetry, times(2)).recordRetention(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Duration.class));
        verify(telemetry).recordRetentionFailure(
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void rejectsRetentionThatWouldImmediatelyEraseOperationalHistory() {
        DatabaseDurableStateProjectionControlPlane controlPlane =
                mock(DatabaseDurableStateProjectionControlPlane.class);

        assertThatThrownBy(() -> new DurableStateProjectionFindingRetentionScheduler(
                controlPlane, Duration.ofMinutes(59), Duration.ofDays(365), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolvedRetention");
        assertThatThrownBy(() -> new DurableStateProjectionFindingRetentionScheduler(
                controlPlane, Duration.ofDays(30), Duration.ofHours(23), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiveRetention");
    }
}
