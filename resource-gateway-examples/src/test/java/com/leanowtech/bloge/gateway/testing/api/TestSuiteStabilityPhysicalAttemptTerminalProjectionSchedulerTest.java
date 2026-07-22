package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
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

class TestSuiteStabilityPhysicalAttemptTerminalProjectionSchedulerTest {

    @Test
    void boundedLanesPollAndStopAfterClose() throws Exception {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class);
        CountDownLatch polled = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(worker.processNext()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            polled.countDown();
            return noWork();
        });
        TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler =
                scheduler(worker, 2);

        assertThat(polled.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.close();
        int callsAfterClose = calls.get();
        Thread.sleep(250);

        TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Snapshot snapshot =
                scheduler.snapshot();
        assertThat(snapshot.closed()).isTrue();
        assertThat(snapshot.activePolls()).isZero();
        assertThat(snapshot.pollCount()).isGreaterThanOrEqualTo(2L);
        assertThat(snapshot.lastOutcome()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.NO_WORK);
        assertThat(calls).hasValue(callsAfterClose);
        verify(worker, atLeast(2)).processNext();
    }

    @Test
    void laneSurvivesOneUnexpectedWorkerException() throws Exception {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(worker.processNext()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("unexpected business detail");
            }
            recovered.countDown();
            return noWork();
        });

        try (TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler =
                     scheduler(worker, 1)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.snapshot().lastPollFailed()).isFalse();
            assertThat(scheduler.snapshot().unexpectedPollCount()).isEqualTo(1L);
        }
    }

    @Test
    void nullResultIsVisibleAndDoesNotKillLane() throws Exception {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(worker.processNext()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return null;
            }
            recovered.countDown();
            return noWork();
        });

        try (TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler =
                     scheduler(worker, 1)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.snapshot().unexpectedPollCount()).isEqualTo(1L);
        }
    }

    @Test
    void closeDrainsAnInFlightPollBeforeDeadline() throws Exception {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.processNext()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return noWork();
        });
        TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler =
                scheduler(worker, 1);
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
    void rejectsUnboundedSchedulingPolicies() {
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler
                .Policy(0, Duration.ZERO, Duration.ofMillis(100), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler
                .Policy(1, Duration.ZERO, Duration.ofMillis(99), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler
                .Policy(1, Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(99)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void telemetryUsesOnlyClosedDimensionsAndReportsLifecycle() throws Exception {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class);
        when(worker.processNext()).thenReturn(noWork());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry telemetry =
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry(registry);
        TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler =
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
                        worker, policy(1), telemetry);
        try {
            awaitCounter(registry, 1.0);
            assertThat(registry.get(metric("worker.configured")).gauge().value())
                    .isEqualTo(1.0);
        } finally {
            scheduler.close();
        }

        assertThat(registry.get(metric("worker.polls"))
                .tag("outcome", "no_work").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(registry.get(metric("worker.local.dispositions"))
                .tag("disposition", "none").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(registry.get(metric("worker.closed")).gauge().value()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("outcome", "disposition")));
    }

    @Test
    void telemetryOutageCannotKillWorkerLane() throws Exception {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class);
        CountDownLatch polledTwice = new CountDownLatch(2);
        when(worker.processNext()).thenAnswer(invocation -> {
            polledTwice.countDown();
            return noWork();
        });
        TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry telemetry = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry.class);
        doThrow(new IllegalStateException("meter outage")).when(telemetry).workerStarted();
        doThrow(new IllegalStateException("meter outage")).when(telemetry).activePolls(anyInt());
        doThrow(new IllegalStateException("meter outage")).when(telemetry)
                .recordPoll(any(TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker
                        .Execution.class));

        try (TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler ignored =
                     new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
                             worker, policy(1), telemetry)) {
            assertThat(polledTwice.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker, int lanes) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
                worker, policy(lanes));
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Policy policy(
            int lanes) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Policy(
                lanes, Duration.ZERO, Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution noWork() {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution
                        .SCHEMA_VERSION,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.NO_WORK,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition.NONE,
                Optional.empty());
    }

    private static String metric(String suffix) {
        return "resource.gateway.test.stability.physical.attempt.terminal.projection." + suffix;
    }

    private static void awaitCounter(
            SimpleMeterRegistry registry, double minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            double count = registry.get(metric("worker.polls"))
                    .tag("outcome", "no_work").counter().count();
            if (count >= minimum) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(registry.get(metric("worker.polls"))
                .tag("outcome", "no_work").counter().count()).isGreaterThanOrEqualTo(minimum);
    }
}
