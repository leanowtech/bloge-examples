package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableStateProjectionSloMonitorTest {

    @Test
    void distinguishesHealthyInitializingAndStablePolicyViolations() {
        DatabaseDurableStateProjectionControlPlane controlPlane =
                mock(DatabaseDurableStateProjectionControlPlane.class);
        Instant observedAt = Instant.parse("2026-07-17T08:00:00Z");
        when(controlPlane.operationalSnapshot(Duration.ofDays(30), Duration.ofDays(365)))
                .thenReturn(snapshot(observedAt, observedAt.minusSeconds(30),
                        observedAt.minusSeconds(60), 0, 0, 0, null));
        DurableStateProjectionSloMonitor monitor = new DurableStateProjectionSloMonitor(
                controlPlane, DurableStateProjectionTelemetry.noop(), policy());

        monitor.refresh();

        Health healthy = monitor.health();
        assertThat(healthy.getStatus()).isEqualTo(Status.UP);
        assertThat(healthy.getDetails()).containsEntry("state", "HEALTHY");
        assertThat(healthy.getDetails()).containsEntry("violations", java.util.List.of());

        when(controlPlane.operationalSnapshot(Duration.ofDays(30), Duration.ofDays(365)))
                .thenReturn(snapshot(observedAt.plusSeconds(30), null, null,
                        0, 0, 0, null));
        monitor.refresh();
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);

        when(controlPlane.operationalSnapshot(Duration.ofDays(30), Duration.ofDays(365)))
                .thenReturn(snapshot(observedAt.plusSeconds(240), observedAt,
                        observedAt.minusSeconds(Duration.ofHours(4).toSeconds()),
                        0, 0, 0, null));
        monitor.refresh();
        assertThat(monitor.health().getDetails().get("violations").toString())
                .contains("RECONCILIATION_STALE", "RETENTION_STALE");

        when(controlPlane.operationalSnapshot(Duration.ofDays(30), Duration.ofDays(365)))
                .thenReturn(snapshot(observedAt.plusSeconds(300), null, null,
                        2, 1, 1, observedAt.minusSeconds(7200)));
        monitor.refresh();

        Health violated = monitor.health();
        assertThat(violated.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violated.getDetails().get("violations").toString())
                .contains("RECONCILIATION_NEVER_SUCCEEDED")
                .contains("RETENTION_NEVER_SUCCEEDED")
                .contains("UNRESOLVED_FINDING_LIMIT_EXCEEDED")
                .contains("UNRESOLVED_FINDING_AGE_EXCEEDED")
                .contains("RESOLVED_RETENTION_BACKLOG_EXCEEDED")
                .contains("ARCHIVE_PURGE_BACKLOG_EXCEEDED");
    }

    @Test
    void reportsStorageFailureWithoutLeakingTheExceptionMessage() {
        DatabaseDurableStateProjectionControlPlane controlPlane =
                mock(DatabaseDurableStateProjectionControlPlane.class);
        when(controlPlane.operationalSnapshot(Duration.ofDays(30), Duration.ofDays(365)))
                .thenThrow(new IllegalStateException("password=do-not-leak"));
        DurableStateProjectionSloMonitor monitor = new DurableStateProjectionSloMonitor(
                controlPlane, DurableStateProjectionTelemetry.noop(), policy());

        monitor.refresh();

        Health health = monitor.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "STORE_UNAVAILABLE")
                .containsEntry("violations", java.util.List.of("PROJECTION_STORE_UNAVAILABLE"));
        assertThat(health.getDetails().toString()).doesNotContain("password", "do-not-leak");
    }

    private static DurableStateProjectionSloMonitor.Policy policy() {
        return new DurableStateProjectionSloMonitor.Policy(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofMinutes(3),
                Duration.ofMinutes(3), Duration.ofHours(3), 0,
                Duration.ofHours(1), 0, 0);
    }

    private static DatabaseDurableStateProjectionControlPlane.OperationalSnapshot snapshot(
            Instant observedAt,
            Instant reconciliationSuccess,
            Instant retentionSuccess,
            long open,
            long overdueResolved,
            long overdueArchive,
            Instant oldestUnresolvedAt) {
        return new DatabaseDurableStateProjectionControlPlane.OperationalSnapshot(
                observedAt,
                new DatabaseDurableStateProjectionControlPlane.ControlSnapshot(
                        DurableStateProjectionReconciler.ScanCursor.start(), "", 1,
                        Instant.EPOCH, 1, reconciliationSuccess),
                new DatabaseDurableStateProjectionControlPlane.RetentionSnapshot(
                        "", 1, Instant.EPOCH, 1, 0, 0, 0,
                        null, null, retentionSuccess),
                open, 0, 0, 0, overdueResolved, overdueArchive, oldestUnresolvedAt);
    }
}
