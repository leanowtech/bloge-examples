package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetHealthTest {

    @Test
    void emptyUnpolledFleetIsReadyAndDetailsRemainAggregateOnly() {
        var health = health(worker(false, false, false), scheduler(
                false, false, false, false));

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsEntry("runtimeStatus", "READY")
                .containsEntry("latestInventoryGeneration", 0L)
                .containsEntry("latestAttemptedLanes", 0);
        assertThat(result.getDetails().keySet()).noneMatch(key -> {
            String lower = key.toLowerCase(Locale.ROOT);
            return lower.contains("scope") || lower.contains("rootset")
                    || lower.contains("workerid") || lower.contains("resolver")
                    || lower.contains("fingerprint") || lower.contains("endpoint")
                    || lower.contains("exception");
        });
    }

    @Test
    void closedLifecycleFailsReadiness() {
        var health = health(worker(true, false, false), scheduler(
                false, false, false, false));

        assertThat(health.health()).satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails()).containsEntry("runtimeStatus", "CLOSED");
        });
    }

    @Test
    void overdueTimerOrCycleIsReportedAsSchedulerStalled() {
        var health = health(worker(false, false, false), scheduler(
                false, true, false, false));

        assertThat(health.health()).satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails())
                    .containsEntry("runtimeStatus", "SCHEDULER_STALLED")
                    .containsEntry("schedulerOverdue", true);
        });
    }

    @Test
    void latestThrownPollIsReportedWithoutFailureDiagnostics() {
        var health = health(worker(false, false, false), scheduler(
                false, false, true, false));

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsEntry("runtimeStatus", "SCHEDULER_FAILED")
                .containsEntry("schedulerPollFailureCount", 1L);
        assertThat(result.getDetails().toString()).doesNotContain("provider-secret");
    }

    @Test
    void cycleWideInventoryFailureAndIsolatedLaneFailureHaveDistinctStatuses() {
        var cycleFailure = health(worker(false, true, false), scheduler(
                false, false, false, false));
        assertThat(cycleFailure.health().getDetails())
                .containsEntry("runtimeStatus", "CYCLE_FAILED");

        var laneFailure = health(worker(false, false, true), scheduler(
                false, false, false, true));
        assertThat(laneFailure.health()).satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails()).containsEntry("runtimeStatus", "LANE_FAILURES")
                    .containsEntry("latestFailedLanes", 1L)
                    .containsEntry("workerLaneFailureCount", 1L);
        });
    }

    @Test
    void snapshotReadFailureCollapsesToBoundedUnavailableShape() {
        var health = new ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth(
                () -> {
                    throw new IllegalStateException("scope-secret provider endpoint");
                }, () -> scheduler(false, false, false, false));

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsOnlyKeys("schemaVersion", "runtimeStatus")
                .containsEntry("runtimeStatus", "UNAVAILABLE");
        assertThat(result.getDetails().toString())
                .doesNotContain("scope-secret", "provider", "endpoint");
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth health(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot scheduler) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth(
                () -> worker, () -> scheduler);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot worker(
            boolean closed, boolean cycleFailed, boolean laneFailed) {
        long cycles = cycleFailed || laneFailed ? 1L : 0L;
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot
                        .SCHEMA_VERSION,
                closed, false, cycles, cycleFailed ? 1L : 0L,
                laneFailed ? 1L : 0L, 0L, laneFailed ? 1L : 0L,
                cycleFailed, laneFailed, laneFailed ? 1L : 0L);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot scheduler(
            boolean closed, boolean overdue, boolean pollFailed, boolean laneFailed) {
        boolean completed = laneFailed;
        long pollCount = pollFailed || completed ? 1L : 0L;
        Instant timestamp = pollCount == 0L ? null : Instant.parse("2026-07-21T00:00:00Z");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot
                        .SCHEMA_VERSION,
                closed, false, overdue, pollCount, completed ? 1L : 0L,
                pollFailed ? 1L : 0L, pollFailed, completed ? 1L : 0L,
                completed ? 1 : 0, 0L, laneFailed ? 1L : 0L, laneFailed,
                timestamp, timestamp, 5_000L, 600_000L);
    }
}
