package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchSchedulerTest {

    @Test
    void pollsOnlyTheConfiguredRegionalPartitionWithStableLaneOwners()
            throws Exception {
        ScenarioRehearsalBatchWorker worker =
                mock(ScenarioRehearsalBatchWorker.class);
        CountDownLatch observed = new CountDownLatch(2);
        List<List<String>> calls =
                new CopyOnWriteArrayList<>();
        when(worker.runOnce(
                anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    calls.add(List.of(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2)));
                    observed.countDown();
                    return noWork();
                });

        try (ScenarioRehearsalBatchScheduler scheduler =
                     scheduler(worker, 2)) {
            assertThat(observed.await(
                    3, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.ready()).isTrue();
            assertThat(scheduler.region()).isEqualTo("sg");
            assertThat(scheduler.environmentId())
                    .isEqualTo("test");
            assertThat(calls)
                    .allSatisfy(call -> {
                        assertThat(call.get(0)).isEqualTo("sg");
                        assertThat(call.get(1)).isEqualTo("test");
                        assertThat(call.get(2))
                                .startsWith("replica-a/lane-");
                    });
            assertThat(calls.stream()
                    .map(call -> call.get(2))
                    .distinct()
                    .count()).isEqualTo(2);
        }
    }

    @Test
    void catchesAnAmbiguousPollWithoutKillingTheLane()
            throws Exception {
        ScenarioRehearsalBatchWorker worker =
                mock(ScenarioRehearsalBatchWorker.class);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(worker.runOnce(
                anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException(
                                "business-payload-must-not-be-logged");
                    }
                    recovered.countDown();
                    return noWork();
                });

        try (ScenarioRehearsalBatchScheduler scheduler =
                     scheduler(worker, 1)) {
            assertThat(recovered.await(
                    3, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
            assertThat(scheduler.ready()).isTrue();
        }
    }

    @Test
    void closeStopsNewClaimsAndDrainsAnActiveTurn()
            throws Exception {
        ScenarioRehearsalBatchWorker worker =
                mock(ScenarioRehearsalBatchWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.runOnce(
                anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    return noWork();
                });
        ScenarioRehearsalBatchScheduler scheduler =
                scheduler(worker, 1);
        assertThat(entered.await(
                3, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.activePolls()).isEqualTo(1);

        try (var closer = Executors.newSingleThreadExecutor()) {
            var closed = closer.submit(scheduler::close);
            assertThatThrownBy(() ->
                    closed.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            closed.get(3, TimeUnit.SECONDS);
        }

        assertThat(scheduler.ready()).isFalse();
        assertThat(scheduler.activePolls()).isZero();
        scheduler.close();
    }

    @Test
    void rejectsProductionPartitionsAndUnboundedPolicies() {
        ScenarioRehearsalBatchWorker worker =
                mock(ScenarioRehearsalBatchWorker.class);

        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchScheduler(
                        worker,
                        "sg",
                        "production",
                        "replica-a",
                        1,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchScheduler(
                        worker,
                        "sg",
                        "test",
                        "replica-a",
                        0,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScenarioRehearsalBatchScheduler scheduler(
            ScenarioRehearsalBatchWorker worker,
            int maximumPollers) {
        return new ScenarioRehearsalBatchScheduler(
                worker,
                "sg",
                "test",
                "replica-a",
                maximumPollers,
                Duration.ZERO,
                Duration.ofMillis(100),
                Duration.ofSeconds(1));
    }

    private static ScenarioRehearsalBatchWorker.Turn noWork() {
        return new ScenarioRehearsalBatchWorker.Turn(
                ScenarioRehearsalBatchWorker.Disposition.NO_WORK,
                null,
                -1,
                "");
    }
}
