package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Assessment;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Policy;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Projection;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Violation;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.actuate.health.Health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private static final Policy POLICY = new Policy(
            Duration.ofSeconds(30), Duration.ofSeconds(30), 20, 500, 500, 1_000);

    @Test
    void initializesThenFailsClosedUntilTheFirstSuccessfulPoll() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<Projection> projection = new AtomicReference<>(projection(
                Status.READY, worker(0, 0, 0, 0, false, false, false),
                scheduler(0, 0, 0, false, false, false, NOW, 0)));
        var monitor = monitor(projection::get, clock);

        assertThat(monitor.assessment().state()).isEqualTo(State.INITIALIZING);
        assertThat(monitor.health().getStatus().getCode()).isEqualTo("UNKNOWN");

        clock.advance(Duration.ofSeconds(31));
        monitor.refresh();

        assertThat(monitor.assessment().state()).isEqualTo(State.SLO_VIOLATED);
        assertThat(monitor.assessment().violations())
                .containsExactly(Violation.POLL_NEVER_SUCCEEDED);
    }

    @Test
    void recentSuccessWithRatesAtInclusiveThresholdIsHealthy() {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(20, 1, 40, 4, false, false, false);
        Snapshot scheduler = scheduler(20, 19, 1, false, false, false,
                NOW.minusSeconds(1), 0);
        var monitor = monitor(() -> projection(Status.READY, worker, scheduler), clock);

        Assessment assessment = monitor.assessment();

        assertThat(assessment.state()).isEqualTo(State.HEALTHY);
        assertThat(assessment.violations()).isEmpty();
        assertThat(assessment.pollFailureBasisPoints()).isEqualTo(500);
        assertThat(assessment.cycleFailureBasisPoints()).isEqualTo(500);
        assertThat(assessment.laneFailureBasisPoints()).isEqualTo(1_000);
        assertThat(assessment.lastPollSuccessAgeMillis()).isEqualTo(1_000L);
    }

    @Test
    void matureHistoricalFailuresViolateEvenWhenLatestRuntimeTruthIsReady() {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(20, 2, 20, 3, false, false, false);
        Snapshot scheduler = scheduler(20, 18, 2, false, false, false,
                NOW.minusSeconds(1), 0);
        var monitor = monitor(() -> projection(Status.READY, worker, scheduler), clock);

        Assessment assessment = monitor.assessment();

        assertThat(assessment.runtimeStatus()).isEqualTo(Status.READY);
        assertThat(assessment.state()).isEqualTo(State.SLO_VIOLATED);
        assertThat(assessment.violations()).containsExactly(
                Violation.POLL_FAILURE_RATE_EXCEEDED,
                Violation.CYCLE_FAILURE_RATE_EXCEEDED,
                Violation.LANE_FAILURE_RATE_EXCEEDED);
    }

    @Test
    void immatureFailureRatiosDoNotBurnStartupBudget() {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(10, 5, 10, 5, false, false, false);
        Snapshot scheduler = scheduler(10, 5, 5, false, false, false,
                NOW.minusSeconds(1), 0);
        var monitor = monitor(() -> projection(Status.READY, worker, scheduler), clock);

        assertThat(monitor.assessment().state()).isEqualTo(State.HEALTHY);
        assertThat(monitor.assessment().violations()).isEmpty();
    }

    @Test
    void staleSuccessFailsButAnAdmittedBoundedCycleDoesNotProduceFalseStaleness() {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(20, 0, 20, 0, false, false, false);
        Snapshot stale = scheduler(20, 20, 0, false, false, false,
                NOW.minusSeconds(31), 0);
        AtomicReference<Projection> projection = new AtomicReference<>(
                projection(Status.READY, worker, stale));
        var monitor = monitor(projection::get, clock);

        assertThat(monitor.assessment().violations())
                .containsExactly(Violation.POLL_SUCCESS_STALE);

        projection.set(projection(Status.READY,
                worker(21, 0, 20, 0, false, false, true),
                scheduler(21, 20, 0, false, true, false,
                        NOW.minusSeconds(31), 0)));
        monitor.refresh();

        assertThat(monitor.assessment().state()).isEqualTo(State.HEALTHY);
        assertThat(monitor.assessment().lastPollSuccessAgeMillis()).isEqualTo(31_000L);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {
            "UNATTESTED_INVENTORY", "INVENTORY_UNAVAILABLE", "SCHEDULER_STALLED",
            "SCHEDULER_FAILED", "CYCLE_FAILED", "LANE_FAILURES", "INCONSISTENT"})
    void currentCapabilityFailureAlwaysViolatesIndependentOfHistoricalRates(Status status) {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(1, status == Status.CYCLE_FAILED ? 1 : 0,
                1, status == Status.LANE_FAILURES ? 1 : 0,
                status == Status.CYCLE_FAILED, status == Status.LANE_FAILURES, false);
        Snapshot scheduler = scheduler(1,
                status == Status.SCHEDULER_FAILED ? 0 : 1,
                status == Status.SCHEDULER_FAILED ? 1 : 0,
                status == Status.SCHEDULER_FAILED,
                false, status == Status.SCHEDULER_STALLED,
                NOW.minusSeconds(1), status == Status.LANE_FAILURES ? 1 : 0);

        var monitor = monitor(() -> projection(status, worker, scheduler), clock);

        assertThat(monitor.assessment().state()).isEqualTo(State.SLO_VIOLATED);
        assertThat(monitor.assessment().violations()).isNotEmpty();
    }

    @Test
    void closedRuntimeHasDistinctDownStateAndRetainsAggregateEvidence() {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(1, 0, 1, 0, false, false, false, true);
        Snapshot scheduler = scheduler(1, 1, 0, false, false, false,
                NOW.minusSeconds(1), 0, true);
        var monitor = monitor(
                () -> projection(Status.RUNTIME_CLOSED, worker, scheduler), clock);

        Assessment assessment = monitor.assessment();

        assertThat(assessment.state()).isEqualTo(State.CLOSED);
        assertThat(assessment.violations()).contains(Violation.RUNTIME_CLOSED);
        assertThat(assessment.pollCount()).isOne();
        assertThat(monitor.health().getStatus()).isEqualTo(
                org.springframework.boot.actuate.health.Status.DOWN);
    }

    @Test
    void authorityCounterTearFailsClosedAsSnapshotInconsistent() {
        MutableClock clock = new MutableClock(NOW);
        RuntimeSnapshot worker = worker(20, 0, 20, 0, false, false, false);
        Snapshot scheduler = scheduler(20, 20, 0, false, false, false,
                NOW.minusSeconds(1), 0);
        var capability = capability(Status.READY, 19, 0, 20, 0, false, false);
        var monitor = monitor(() -> new Projection(capability, worker, scheduler), clock);

        assertThat(monitor.assessment().violations())
                .containsExactly(Violation.SNAPSHOT_INCONSISTENT);
    }

    @Test
    void observationExceptionIsPayloadFreeAndRecoverable() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<Boolean> fails = new AtomicReference<>(true);
        var monitor = monitor(() -> {
            if (fails.get()) {
                throw new IllegalStateException("secret://must-not-escape");
            }
            return projection(Status.READY,
                    worker(1, 0, 1, 0, false, false, false),
                    scheduler(1, 1, 0, false, false, false,
                            NOW.minusSeconds(1), 0));
        }, clock);

        Assessment unavailable = monitor.assessment();
        Health health = monitor.health();

        assertThat(unavailable.state()).isEqualTo(State.OBSERVATION_UNAVAILABLE);
        assertThat(unavailable.observedAt()).isNull();
        assertThat(unavailable.pollCount()).isEqualTo(-1L);
        assertThat(health.getDetails().toString()).doesNotContain("secret", "must-not-escape");

        fails.set(false);
        monitor.refresh();
        assertThat(monitor.assessment().state()).isEqualTo(State.HEALTHY);
    }

    @Test
    void policyAndAssessmentRejectUnsafeOrForgedRelationships() {
        assertThatThrownBy(() -> new Policy(
                Duration.ZERO, Duration.ofSeconds(1), 1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Policy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Policy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 10_001, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        var descriptor = POLICY.descriptor();
        assertThatThrownBy(() -> new Assessment(
                Assessment.SCHEMA_VERSION, State.HEALTHY,
                List.of(Violation.SCHEDULER_FAILED), NOW, Status.READY,
                1, 0, 1, 1, 0, 0, 1, 0, 0,
                1, 0, 0, 0, descriptor))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Assessment(
                Assessment.SCHEMA_VERSION, State.HEALTHY, List.of(), NOW, Status.READY,
                1, 0, 10, 9, 1, 999, 10, 0, 0,
                10, 0, 0, 0, descriptor))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Assessment(
                Assessment.SCHEMA_VERSION, State.SLO_VIOLATED,
                List.of(Violation.OBSERVATION_UNAVAILABLE), NOW, Status.UNAVAILABLE,
                1, 0, 1, 1, 0, 0, 1, 0, 0,
                1, 0, 0, 0, descriptor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor monitor(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.ObservationReader reader,
            Clock clock) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor(
                reader, ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry.noop(),
                POLICY, clock);
    }

    private static Projection projection(
            Status status, RuntimeSnapshot worker, Snapshot scheduler) {
        var capability = status == Status.UNATTESTED_INVENTORY
                ? ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.unattested()
                : capability(status, scheduler.pollCount(), scheduler.pollFailureCount(),
                worker.cycleCount(), worker.cycleFailureCount(),
                scheduler.active(), scheduler.overdue());
        return new Projection(capability, worker, scheduler);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability capability(
            Status status,
            long polls,
            long pollFailures,
            long cycles,
            long cycleFailures,
            boolean schedulerActive,
            boolean schedulerOverdue) {
        boolean inventoryAvailable = status != Status.INVENTORY_UNAVAILABLE;
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION,
                true, status == Status.READY, status, true, inventoryAvailable,
                "STATIC_SIGNED_ED25519_M_OF_N", 17L, 2,
                false, false, false, false, false, false, false,
                schedulerActive, schedulerOverdue, polls, pollFailures, cycles, cycleFailures);
    }

    private static RuntimeSnapshot worker(
            long cycles,
            long cycleFailures,
            long laneAttempts,
            long laneFailures,
            boolean lastCycleFailed,
            boolean latestLaneFailures,
            boolean active) {
        return worker(cycles, cycleFailures, laneAttempts, laneFailures,
                lastCycleFailed, latestLaneFailures, active, false);
    }

    private static RuntimeSnapshot worker(
            long cycles,
            long cycleFailures,
            long laneAttempts,
            long laneFailures,
            boolean lastCycleFailed,
            boolean latestLaneFailures,
            boolean active,
            boolean closed) {
        return new RuntimeSnapshot(RuntimeSnapshot.SCHEMA_VERSION, closed, active,
                cycles, cycleFailures, laneAttempts, 0, laneFailures,
                lastCycleFailed, latestLaneFailures, cycles == 0 ? 0 : 17);
    }

    private static Snapshot scheduler(
            long polls,
            long completed,
            long failed,
            boolean lastPollFailed,
            boolean active,
            boolean overdue,
            Instant lastCompletedAt,
            long latestFailedLanes) {
        return scheduler(polls, completed, failed, lastPollFailed, active, overdue,
                lastCompletedAt, latestFailedLanes, false);
    }

    private static Snapshot scheduler(
            long polls,
            long completed,
            long failed,
            boolean lastPollFailed,
            boolean active,
            boolean overdue,
            Instant lastCompletedAt,
            long latestFailedLanes,
            boolean closed) {
        Instant started = polls == 0 ? null
                : active ? NOW : lastCompletedAt.minusMillis(1);
        Instant completedAt = completed + failed == 0 ? null : lastCompletedAt;
        return new Snapshot(Snapshot.SCHEMA_VERSION, closed, active, overdue,
                polls, completed, failed, lastPollFailed, completed == 0 ? 0 : 17,
                latestFailedLanes == 0 ? 0 : 1, 0, latestFailedLanes,
                latestFailedLanes > 0, started, completedAt, 1_000, 10_000);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
