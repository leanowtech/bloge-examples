package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.SchedulePolicy;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.CycleResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.LaneResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetSchedulerTest {

    @Test
    void completedCyclePublishesExactBoundedAggregateShape() {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        when(worker.runCycle()).thenReturn(mixedCycle(7L));
        try (var scheduler = manualScheduler(worker, mutableClock())) {
            assertThat(scheduler.runOnce().inventoryGeneration()).isEqualTo(7L);

            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isOne();
                assertThat(snapshot.completedPollCount()).isOne();
                assertThat(snapshot.pollFailureCount()).isZero();
                assertThat(snapshot.latestInventoryGeneration()).isEqualTo(7L);
                assertThat(snapshot.latestAttemptedLanes()).isEqualTo(2);
                assertThat(snapshot.latestAcquiredLanes()).isOne();
                assertThat(snapshot.latestFailedLanes()).isOne();
                assertThat(snapshot.latestCycleHadLaneFailures()).isTrue();
            });
        }
    }

    @Test
    void runtimeFailureIsCountedAndALaterCleanCycleClearsLatestFailure() {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        when(worker.runCycle())
                .thenThrow(new IllegalStateException("inventory endpoint must not escape"))
                .thenReturn(emptyCycle(8L));
        try (var scheduler = manualScheduler(worker, mutableClock())) {
            assertThatThrownBy(scheduler::runOnce)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isTrue();
                assertThat(snapshot.latestInventoryGeneration()).isZero();
            });

            scheduler.runOnce();
            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isEqualTo(2L);
                assertThat(snapshot.completedPollCount()).isOne();
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isFalse();
                assertThat(snapshot.latestCycleHadLaneFailures()).isFalse();
            });
        }
    }

    @Test
    void backgroundRuntimeFailureDoesNotStopTheNextFixedDelayCycle() throws Exception {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        when(worker.runCycle())
                .thenThrow(new IllegalStateException("temporary inventory outage"))
                .thenReturn(emptyCycle(3L));
        var policy = new SchedulePolicy(Duration.ZERO, Duration.ofMillis(100),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        try (var scheduler = new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
                worker, policy)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (scheduler.snapshot().completedPollCount() == 0L
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isGreaterThanOrEqualTo(2L);
                assertThat(snapshot.completedPollCount()).isGreaterThanOrEqualTo(1L);
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isFalse();
            });
        }
    }

    @Test
    void scheduledFatalFailureIsPublishedBeforeThePeriodicTaskTerminates() throws Exception {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        when(worker.runCycle()).thenThrow(new AssertionError("fatal worker state"));
        var policy = new SchedulePolicy(Duration.ZERO, Duration.ofHours(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        try (var scheduler = new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
                worker, policy)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (scheduler.snapshot().pollFailureCount() == 0L
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            long polls = scheduler.snapshot().pollCount();
            Thread.sleep(150L);

            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isEqualTo(polls).isOne();
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isTrue();
            });
        }
    }

    @Test
    void closeWaitsForAdmittedCycleAndNeverClosesCallerOwnedWorker() throws Exception {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.runCycle()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return emptyCycle(1L);
        });
        var scheduler = manualScheduler(worker, mutableClock());
        Thread cycle = Thread.ofPlatform().start(scheduler::runOnce);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            scheduler.close();
            closeReturned.countDown();
        });

        assertThat(closeReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(scheduler.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.closed()).isTrue();
            assertThat(snapshot.active()).isTrue();
        });
        release.countDown();
        cycle.join(Duration.ofSeconds(2));
        closer.join(Duration.ofSeconds(2));
        scheduler.close();

        assertThat(closeReturned.getCount()).isZero();
        assertThatThrownBy(scheduler::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap-root recovery fleet scheduler is closed");
        verify(worker, times(1)).runCycle();
        verify(worker, never()).close();
    }

    @Test
    void pollSubmittedAfterCloseFailsWithoutWaitingForTheActiveCycle() throws Exception {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.runCycle()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return emptyCycle(1L);
        });
        var scheduler = manualScheduler(worker, mutableClock());
        Thread cycle = Thread.ofPlatform().start(scheduler::runOnce);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread closer = Thread.ofPlatform().start(scheduler::close);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!scheduler.snapshot().closed() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(scheduler.snapshot().closed()).isTrue();

        AtomicReference<Throwable> rejection = new AtomicReference<>();
        CountDownLatch rejected = new CountDownLatch(1);
        Thread latePoll = Thread.ofPlatform().start(() -> {
            try {
                scheduler.runOnce();
            } catch (Throwable failure) {
                rejection.set(failure);
            } finally {
                rejected.countDown();
            }
        });
        assertThat(rejected.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(rejection.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap-root recovery fleet scheduler is closed");

        release.countDown();
        latePoll.join(Duration.ofSeconds(2));
        cycle.join(Duration.ofSeconds(2));
        closer.join(Duration.ofSeconds(2));
        assertThat(latePoll.isAlive()).isFalse();
        assertThat(cycle.isAlive()).isFalse();
        assertThat(closer.isAlive()).isFalse();
        verify(worker, times(1)).runCycle();
    }

    @Test
    void reentrantCloseIsRejectedWithoutPoisoningSchedulerLifecycle() {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        AtomicReference<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler> reference =
                new AtomicReference<>();
        when(worker.runCycle()).thenAnswer(invocation -> {
            assertThatThrownBy(reference.get()::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap-root recovery fleet scheduler cannot close reentrantly");
            return emptyCycle(1L);
        });
        var scheduler = manualScheduler(worker, mutableClock());
        reference.set(scheduler);

        scheduler.runOnce();

        assertThat(scheduler.snapshot().closed()).isFalse();
        assertThat(scheduler.snapshot().completedPollCount()).isOne();
        scheduler.close();
        assertThat(scheduler.snapshot().closed()).isTrue();
    }

    @Test
    void externalCloseAndReentrantCallbackCannotCreateALockInversion() throws Exception {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        AtomicReference<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler> reference =
                new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch attemptReentrantClose = new CountDownLatch(1);
        when(worker.runCycle()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(attemptReentrantClose.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(reference.get()::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bootstrap-root recovery fleet scheduler cannot close reentrantly");
            return emptyCycle(1L);
        });
        var scheduler = manualScheduler(worker, mutableClock());
        reference.set(scheduler);
        Thread cycle = Thread.ofPlatform().start(scheduler::runOnce);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread closer = Thread.ofPlatform().start(scheduler::close);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!scheduler.snapshot().closed() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(scheduler.snapshot().closed()).isTrue();

        attemptReentrantClose.countDown();
        cycle.join(Duration.ofSeconds(2));
        closer.join(Duration.ofSeconds(2));

        assertThat(cycle.isAlive()).isFalse();
        assertThat(closer.isAlive()).isFalse();
        assertThat(scheduler.snapshot().completedPollCount()).isOne();
    }

    @Test
    void concurrentExplicitPollsAreSerializedBeforeWorkerAdmission() throws Exception {
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger workerAdmissions = new AtomicInteger();
        when(worker.runCycle()).thenAnswer(invocation -> {
            workerAdmissions.incrementAndGet();
            firstEntered.countDown();
            assertThat(releaseFirst.await(2, TimeUnit.SECONDS)).isTrue();
            return emptyCycle(1L);
        }).thenAnswer(invocation -> {
            workerAdmissions.incrementAndGet();
            return emptyCycle(1L);
        });
        try (var scheduler = manualScheduler(worker, mutableClock())) {
            Thread first = Thread.ofPlatform().start(scheduler::runOnce);
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            CountDownLatch secondCompleted = new CountDownLatch(1);
            Thread second = Thread.ofPlatform().start(() -> {
                scheduler.runOnce();
                secondCompleted.countDown();
            });

            assertThat(secondCompleted.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(workerAdmissions).hasValue(1);
            releaseFirst.countDown();
            first.join(Duration.ofSeconds(2));
            second.join(Duration.ofSeconds(2));

            assertThat(secondCompleted.getCount()).isZero();
            assertThat(workerAdmissions).hasValue(2);
            verify(worker, times(2)).runCycle();
            assertThat(scheduler.snapshot().completedPollCount()).isEqualTo(2L);
        }
    }

    @Test
    void snapshotDetectsIdleTimerStallAndActiveCycleBudgetOverrun() throws Exception {
        MutableClock idleClock = mutableClock();
        var idleWorker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        when(idleWorker.runCycle()).thenReturn(emptyCycle(1L));
        try (var scheduler = manualScheduler(idleWorker, idleClock)) {
            assertThat(scheduler.snapshot().overdue()).isFalse();
            idleClock.advance(Duration.ofDays(1).plusMillis(101));
            assertThat(scheduler.snapshot().overdue()).isTrue();
        }

        MutableClock activeClock = mutableClock();
        var activeWorker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(activeWorker.runCycle()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return emptyCycle(2L);
        });
        var scheduler = manualScheduler(activeWorker, activeClock);
        Thread cycle = Thread.ofPlatform().start(scheduler::runOnce);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        activeClock.advance(Duration.ofSeconds(2));

        assertThat(scheduler.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.active()).isTrue();
            assertThat(snapshot.overdue()).isTrue();
        });
        release.countDown();
        cycle.join(Duration.ofSeconds(2));
        scheduler.close();
    }

    @Test
    void wallClockRollbackCannotPublishCompletionBeforeCycleStart() {
        MutableClock clock = mutableClock();
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        when(worker.runCycle()).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(-5));
            return emptyCycle(1L);
        });
        try (var scheduler = manualScheduler(worker, clock)) {
            scheduler.runOnce();

            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.lastPollStartedAt()).isNotNull();
                assertThat(snapshot.lastPollCompletedAt())
                        .isEqualTo(snapshot.lastPollStartedAt());
            });
        }
    }

    @Test
    void activePollRetainsThePreviousPollCompletionTimestamp() throws Exception {
        MutableClock clock = mutableClock();
        var worker = mock(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        when(worker.runCycle()).thenReturn(emptyCycle(1L)).thenAnswer(invocation -> {
            secondEntered.countDown();
            assertThat(releaseSecond.await(2, TimeUnit.SECONDS)).isTrue();
            return emptyCycle(1L);
        });
        var scheduler = manualScheduler(worker, clock);
        scheduler.runOnce();
        Instant firstCompletion = scheduler.snapshot().lastPollCompletedAt();
        clock.advance(Duration.ofSeconds(1));
        Thread second = Thread.ofPlatform().start(scheduler::runOnce);
        assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(scheduler.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.active()).isTrue();
            assertThat(snapshot.pollCount()).isEqualTo(2L);
            assertThat(snapshot.completedPollCount()).isOne();
            assertThat(snapshot.lastPollCompletedAt()).isEqualTo(firstCompletion)
                    .isBefore(snapshot.lastPollStartedAt());
        });
        releaseSecond.countDown();
        second.join(Duration.ofSeconds(2));
        scheduler.close();
    }

    @Test
    void policyAndSnapshotRejectImpossibleBounds() {
        assertThatThrownBy(() -> new SchedulePolicy(Duration.ZERO,
                Duration.ofMillis(99), Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SchedulePolicy(Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofMillis(999), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler
                .Snapshot(ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot
                .SCHEMA_VERSION, false, false, false, 1L, 0L, 0L, false,
                0L, 0, 0L, 0L, false, Instant.EPOCH, null, 100L, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler manualScheduler(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            MutableClock clock) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
                worker, new SchedulePolicy(Duration.ofDays(1), Duration.ofMillis(100),
                Duration.ofSeconds(1), Duration.ZERO), clock, false);
    }

    private static CycleResult mixedCycle(long generation) {
        return new CycleResult(CycleResult.SCHEMA_VERSION, generation, List.of(
                new LaneResult(new LaneKey("tenant", "roots-a"), RecoveryStatus.EXECUTED,
                        ExecutionStatus.PRODUCED, false),
                new LaneResult(new LaneKey("tenant", "roots-b"), null, null, true)));
    }

    private static CycleResult emptyCycle(long generation) {
        return new CycleResult(CycleResult.SCHEMA_VERSION, generation, List.of());
    }

    private static MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported by the test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
