package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchFinalizationSchedulerTest {

    @Test
    void pollsOnlyTheConfiguredPartitionWithDedicatedFinalizerOwners()
            throws Exception {
        ScenarioRehearsalBatchFinalizationWorker worker =
                mock(
                        ScenarioRehearsalBatchFinalizationWorker
                                .class);
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

        try (ScenarioRehearsalBatchFinalizationScheduler
                     scheduler = scheduler(worker, 2)) {
            assertThat(observed.await(
                    3, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.ready()).isTrue();
            assertThat(scheduler.region()).isEqualTo("sg");
            assertThat(scheduler.environmentId())
                    .isEqualTo("test");
            assertThat(calls).allSatisfy(call -> {
                assertThat(call.get(0)).isEqualTo("sg");
                assertThat(call.get(1)).isEqualTo("test");
                assertThat(call.get(2))
                        .startsWith(
                                "replica-a/finalizer-");
            });
            assertThat(calls.stream()
                    .map(call -> call.get(2))
                    .distinct()
                    .count()).isEqualTo(2);
        }
    }

    @Test
    void catchesOnePollFailureWithoutKillingTheLane()
            throws Exception {
        ScenarioRehearsalBatchFinalizationWorker worker =
                mock(
                        ScenarioRehearsalBatchFinalizationWorker
                                .class);
        CountDownLatch recovered = new CountDownLatch(1);
        when(worker.runOnce(
                anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException(
                        "must-not-be-logged"))
                .thenAnswer(invocation -> {
                    recovered.countDown();
                    return noWork();
                });

        try (ScenarioRehearsalBatchFinalizationScheduler
                     scheduler = scheduler(worker, 1)) {
            assertThat(recovered.await(
                    3, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.ready()).isTrue();
        }
    }

    @Test
    void rejectsProductionAndUnboundedKmsConcurrency() {
        ScenarioRehearsalBatchFinalizationWorker worker =
                mock(
                        ScenarioRehearsalBatchFinalizationWorker
                                .class);

        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationScheduler(
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
                new ScenarioRehearsalBatchFinalizationScheduler(
                        worker,
                        "sg",
                        "test",
                        "replica-a",
                        33,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScenarioRehearsalBatchFinalizationScheduler
    scheduler(
            ScenarioRehearsalBatchFinalizationWorker worker,
            int pollers) {
        return new ScenarioRehearsalBatchFinalizationScheduler(
                worker,
                "sg",
                "test",
                "replica-a",
                pollers,
                Duration.ZERO,
                Duration.ofMillis(100),
                Duration.ofSeconds(1));
    }

    private static ScenarioRehearsalBatchFinalizationWorker.Turn
    noWork() {
        return new ScenarioRehearsalBatchFinalizationWorker.Turn(
                ScenarioRehearsalBatchFinalizationWorker
                        .Disposition.NO_WORK,
                "",
                0,
                "",
                null,
                null);
    }
}
