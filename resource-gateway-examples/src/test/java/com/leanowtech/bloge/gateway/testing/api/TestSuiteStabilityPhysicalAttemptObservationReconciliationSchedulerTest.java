package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptObservationReconciliationSchedulerTest {

    @Test
    void boundedLanesPollAndStopAfterClose() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        CountDownLatch polled = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(reconciler.reconcileNext("observation-worker-a")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            polled.countDown();
            return noWork();
        });
        TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler scheduler =
                scheduler(reconciler, 2);

        assertThat(polled.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.close();
        int callsAfterClose = calls.get();
        Thread.sleep(250);

        var snapshot = scheduler.snapshot();
        assertThat(snapshot.closed()).isTrue();
        assertThat(snapshot.activePolls()).isZero();
        assertThat(snapshot.pollCount()).isGreaterThanOrEqualTo(2L);
        assertThat(snapshot.lastStage()).contains(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.NO_WORK);
        assertThat(calls).hasValue(callsAfterClose);
        verify(reconciler, atLeast(2)).reconcileNext("observation-worker-a");
    }

    @Test
    void laneSurvivesOneUnexpectedReconcilerException() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(reconciler.reconcileNext("observation-worker-a")).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("unexpected provider detail");
            }
            recovered.countDown();
            return noWork();
        });

        try (var scheduler = scheduler(reconciler, 1)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            awaitCommittedRecovery(scheduler);
            assertThat(scheduler.snapshot().lastPollFailed()).isFalse();
            assertThat(scheduler.snapshot().unexpectedPollCount()).isEqualTo(1L);
        }
    }

    @Test
    void nullResultIsVisibleAndDoesNotKillLane() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(reconciler.reconcileNext("observation-worker-a")).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return null;
            }
            recovered.countDown();
            return noWork();
        });

        try (var scheduler = scheduler(reconciler, 1)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.snapshot().unexpectedPollCount()).isEqualTo(1L);
        }
    }

    @Test
    void closeDrainsAnInFlightPollBeforeDeadline() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reconciler.reconcileNext("observation-worker-a")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return noWork();
        });
        var scheduler = scheduler(reconciler, 1);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var closing = pool.submit(scheduler::close);
            Thread.sleep(100);
            assertThat(closing.isDone()).isFalse();

            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertThat(scheduler.snapshot().activePolls()).isZero();
        } finally {
            release.countDown();
            scheduler.close();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidIdentityAndUnboundedPolicies() {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler(
                reconciler, "credential value", policy(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy(
                0, Duration.ZERO, Duration.ofMillis(100), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy(
                1, Duration.ZERO, Duration.ofMillis(99), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy(
                1, Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(99)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void telemetryUsesOnlyClosedStageAndReportsLifecycle() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        when(reconciler.reconcileNext("observation-worker-a")).thenReturn(noWork());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var telemetry =
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry(registry);
        var scheduler =
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler(
                        reconciler, "observation-worker-a", policy(1), telemetry);
        try {
            awaitCounter(registry, 1.0);
            assertThat(registry.get(metric("worker.configured")).gauge().value())
                    .isEqualTo(1.0);
        } finally {
            scheduler.close();
        }

        assertThat(registry.get(metric("worker.polls"))
                .tag("stage", "no_work").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(registry.get(metric("worker.closed")).gauge().value()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isEqualTo("stage")));
    }

    @Test
    void telemetryOutageCannotKillReconciliationLane() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.class);
        CountDownLatch polledTwice = new CountDownLatch(2);
        when(reconciler.reconcileNext("observation-worker-a")).thenAnswer(invocation -> {
            polledTwice.countDown();
            return noWork();
        });
        var telemetry = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry.class);
        doThrow(new IllegalStateException("meter outage")).when(telemetry).workerStarted();
        doThrow(new IllegalStateException("meter outage")).when(telemetry).activePolls(anyInt());
        doThrow(new IllegalStateException("meter outage")).when(telemetry)
                .recordPoll(any(TestSuiteStabilityPhysicalAttemptObservationReconciler
                        .Attempt.class));

        try (var ignored =
                     new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler(
                             reconciler, "observation-worker-a", policy(1), telemetry)) {
            assertThat(polledTwice.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler scheduler(
            TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler, int lanes) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler(
                reconciler, "observation-worker-a", policy(lanes));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy
            policy(int lanes) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy(
                lanes, Duration.ZERO, Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciler.Attempt noWork() {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciler.Attempt(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.NO_WORK, 0, 0);
    }

    private static String metric(String suffix) {
        return "resource.gateway.test.stability.physical.attempt.observation.reconciliation."
                + suffix;
    }

    private static void awaitCounter(
            SimpleMeterRegistry registry, double minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            double count = registry.get(metric("worker.polls"))
                    .tag("stage", "no_work").counter().count();
            if (count >= minimum) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(registry.get(metric("worker.polls"))
                .tag("stage", "no_work").counter().count()).isGreaterThanOrEqualTo(minimum);
    }

    private static void awaitCommittedRecovery(
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler scheduler)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var snapshot = scheduler.snapshot();
            if (snapshot.unexpectedPollCount() == 1L && !snapshot.lastPollFailed()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(scheduler.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.unexpectedPollCount()).isEqualTo(1L);
            assertThat(snapshot.lastPollFailed()).isFalse();
        });
    }
}
