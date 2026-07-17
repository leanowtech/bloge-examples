package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableRecoverySequenceRetentionSloMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void transitionsFromStartupGraceToNeverSucceededViolationUsingDatabaseTime() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry telemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        when(checkpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenReturn(snapshot(NOW, null, 0, null, 0, null))
                .thenReturn(snapshot(NOW.plusSeconds(181), null, 0, null, 0, null));
        var monitor = new DurableRecoverySequenceRetentionSloMonitor(
                checkpoints, telemetry, policy());

        monitor.refresh();
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violations(monitor)).containsExactly(
                "RETENTION_NEVER_SUCCEEDED");
    }

    @Test
    void reportsHealthyOnlyWhenFreshAndBothBacklogsSatisfyPolicy() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry telemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        var healthy = snapshot(NOW, NOW.minusSeconds(60), 0, null, 0, null);
        when(checkpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenReturn(healthy);
        var monitor = new DurableRecoverySequenceRetentionSloMonitor(
                checkpoints, telemetry, policy());

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(violations(monitor)).isEmpty();
        verify(telemetry).observeSlo(
                healthy, DurableRecoverySequenceRetentionSloMonitor.State.HEALTHY,
                Duration.ofSeconds(60), null, null);
    }

    @Test
    void emitsEveryStableFreshnessCountAndAgeViolationWithoutIdentities() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry telemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        when(checkpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenReturn(snapshot(NOW, NOW.minus(Duration.ofHours(4)),
                        2, NOW.minus(Duration.ofHours(2)),
                        3, NOW.minus(Duration.ofHours(2))));
        var monitor = new DurableRecoverySequenceRetentionSloMonitor(
                checkpoints, telemetry, policy());

        monitor.refresh();

        assertThat(monitor.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violations(monitor)).containsExactly(
                "RETENTION_STALE",
                "SEQUENCE_RETENTION_BACKLOG_EXCEEDED",
                "SEQUENCE_RETENTION_BACKLOG_STALE",
                "TOMBSTONE_PURGE_BACKLOG_EXCEEDED",
                "TOMBSTONE_PURGE_BACKLOG_STALE");
        assertThat(monitor.health().getDetails())
                .containsEntry("overdueSequences", 2L)
                .containsEntry("expiredTombstones", 3L)
                .doesNotContainKeys("tenantId", "runId", "clientRequestId", "error");
    }

    @Test
    void failsClosedOnStoreOutageButNotOnPostObservationTelemetryFailure() {
        DurableTestExecutionCheckpointRepository checkpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry telemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        when(checkpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenThrow(new IllegalStateException("database secret"));
        var unavailable = new DurableRecoverySequenceRetentionSloMonitor(
                checkpoints, telemetry, policy());

        unavailable.refresh();

        assertThat(unavailable.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(violations(unavailable)).containsExactly(
                "RETENTION_STORE_UNAVAILABLE");
        assertThat(unavailable.health().getDetails().toString())
                .doesNotContain("database secret");
        verify(telemetry).observeStoreUnavailable();

        DurableTestExecutionCheckpointRepository healthyCheckpoints =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableRecoverySequenceRetentionTelemetry brokenTelemetry =
                mock(DurableRecoverySequenceRetentionTelemetry.class);
        when(healthyCheckpoints.recoverySequenceRetentionSnapshot(Duration.ofDays(30)))
                .thenReturn(snapshot(NOW, NOW.minusSeconds(60), 0, null, 0, null));
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(brokenTelemetry).observeSlo(any(), any(), any(), any(), any());
        var telemetryFailure = new DurableRecoverySequenceRetentionSloMonitor(
                healthyCheckpoints, brokenTelemetry, policy());

        telemetryFailure.refresh();

        assertThat(telemetryFailure.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void rejectsUnsafeScheduleDurationAndBacklogPoliciesAtAssemblyTime() {
        assertThatThrownBy(() -> new DurableRecoverySequenceRetentionSloMonitor.Policy(
                Duration.ofDays(30), Duration.ofMillis(999), Duration.ofMinutes(3),
                Duration.ofHours(3), 0, Duration.ofHours(1),
                0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observationInterval");
        assertThatThrownBy(() -> new DurableRecoverySequenceRetentionSloMonitor.Policy(
                Duration.ofDays(30), Duration.ofSeconds(30), Duration.ofMinutes(3),
                Duration.ofHours(3), -1, Duration.ofHours(1),
                0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    private static DurableRecoverySequenceRetentionSloMonitor.Policy policy() {
        return new DurableRecoverySequenceRetentionSloMonitor.Policy(
                Duration.ofDays(30), Duration.ofSeconds(30), Duration.ofMinutes(3),
                Duration.ofHours(3), 0, Duration.ofHours(1),
                0, Duration.ofHours(1));
    }

    private static DurableTestExecutionCheckpointRepository
    .RecoverySequenceRetentionSnapshot snapshot(
            Instant observedAt,
            Instant lastSuccessAt,
            long overdueSequences,
            Instant oldestOverdueSequence,
            long expiredTombstones,
            Instant oldestExpiredTombstone) {
        return new DurableTestExecutionCheckpointRepository
                .RecoverySequenceRetentionSnapshot(
                "", 1, Instant.EPOCH, 2,
                4, 3, 2, 6, 1, 5, 3,
                overdueSequences, expiredTombstones,
                oldestOverdueSequence, oldestExpiredTombstone,
                lastSuccessAt, observedAt);
    }

    @SuppressWarnings("unchecked")
    private static List<String> violations(
            DurableRecoverySequenceRetentionSloMonitor monitor) {
        return (List<String>) monitor.health().getDetails().get("violations");
    }
}
