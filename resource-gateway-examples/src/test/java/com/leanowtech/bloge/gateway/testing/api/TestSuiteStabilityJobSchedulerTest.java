package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobSchedulerTest {

    @Test
    void boundedLanesPollEnabledEnvironmentsAndStopAfterClose() throws Exception {
        TestSuiteStabilityJobWorker worker = mock(TestSuiteStabilityJobWorker.class);
        CountDownLatch polled = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(worker.processNext(anyString())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            polled.countDown();
            return TestSuiteStabilityJobWorkResult.noWork();
        });
        TestSuiteStabilityJobScheduler scheduler = scheduler(worker, 2);

        assertThat(polled.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.close();
        int callsAfterClose = calls.get();
        Thread.sleep(250);

        assertThat(scheduler.closed()).isTrue();
        assertThat(scheduler.activePolls()).isZero();
        assertThat(calls).hasValue(callsAfterClose);
        verify(worker, atLeast(2)).processNext(anyString());
    }

    @Test
    void laneSurvivesOneUnexpectedWorkerException() throws Exception {
        TestSuiteStabilityJobWorker worker = mock(TestSuiteStabilityJobWorker.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(worker.processNext("test")).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("unexpected");
            }
            recovered.countDown();
            return TestSuiteStabilityJobWorkResult.noWork();
        });

        try (TestSuiteStabilityJobScheduler scheduler = scheduler(worker, 1)) {
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void closeDrainsAnInFlightPollBeforeItsDeadline() throws Exception {
        TestSuiteStabilityJobWorker worker = mock(TestSuiteStabilityJobWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.processNext("test")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return TestSuiteStabilityJobWorkResult.noWork();
        });
        TestSuiteStabilityJobScheduler scheduler = scheduler(worker, 1);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var closing = pool.submit(scheduler::close);
            Thread.sleep(100);
            assertThat(closing.isDone()).isFalse();

            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertThat(scheduler.activePolls()).isZero();
        } finally {
            release.countDown();
            scheduler.close();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsUnboundedOrProductionSchedulingCoordinates() {
        TestSuiteStabilityJobWorker worker = mock(TestSuiteStabilityJobWorker.class);

        assertThatThrownBy(() -> new TestSuiteStabilityJobScheduler(
                worker, Set.of("production"), 1, Duration.ZERO,
                Duration.ofMillis(100), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityJobScheduler(
                worker, Set.of("test"), 0, Duration.ZERO,
                Duration.ofMillis(100), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityJobScheduler(
                worker, Set.of("test", "staging"), 1, Duration.ZERO,
                Duration.ofMillis(100), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one polling lane");
    }

    private static TestSuiteStabilityJobScheduler scheduler(
            TestSuiteStabilityJobWorker worker,
            int lanes) {
        return new TestSuiteStabilityJobScheduler(
                worker, Set.of("test"), lanes, Duration.ZERO,
                Duration.ofMillis(100), Duration.ofSeconds(2));
    }
}
