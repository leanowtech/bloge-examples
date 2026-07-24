package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCapacityTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MirrorSessionWriteAttemptReconciliationSchedulerTest {
    private static final String SWEEP_METER =
            "resource.gateway.mirror.session."
                    + "write.attempt.reconciliation.sweeps";

    @Test
    void preventsLocalOverlapAndPublishesBoundedTelemetry()
            throws Exception {
        MirrorSessionStateStore store =
                mock(MirrorSessionStateStore.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorSessionCapacityTelemetry telemetry =
                new MirrorSessionCapacityTelemetry(meters);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.reconcileWriteAttempts(25))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    release.await();
                    return 3;
                });
        MirrorSessionWriteAttemptReconciliationScheduler scheduler =
                new MirrorSessionWriteAttemptReconciliationScheduler(
                        store, telemetry, 25, 5_000);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(scheduler::sweep);
            entered.await();
            scheduler.sweep();
            release.countDown();
            first.get();
        }

        verify(store).reconcileWriteAttempts(25);
        assertThat(counter(meters, "succeeded")).isEqualTo(1);
        assertThat(counter(meters, "skipped")).isEqualTo(1);
        assertThat(meters.get(
                        "resource.gateway.mirror.session."
                                + "write.attempt.reconciliation."
                                + "last.terminalized")
                .gauge().value()).isEqualTo(3);
    }

    @Test
    void containsStoreFailureAndKeepsTheNextTickRunnable() {
        MirrorSessionStateStore store =
                mock(MirrorSessionStateStore.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorSessionCapacityTelemetry telemetry =
                new MirrorSessionCapacityTelemetry(meters);
        when(store.reconcileWriteAttempts(10))
                .thenThrow(new IllegalStateException(
                        "database unavailable"))
                .thenReturn(2);
        MirrorSessionWriteAttemptReconciliationScheduler scheduler =
                new MirrorSessionWriteAttemptReconciliationScheduler(
                        store, telemetry, 10, 5_000);

        scheduler.sweep();
        scheduler.sweep();

        assertThat(counter(meters, "failed")).isEqualTo(1);
        assertThat(counter(meters, "succeeded")).isEqualTo(1);
        assertThat(meters.get(
                        "resource.gateway.mirror.session."
                                + "write.attempt.reconciliation."
                                + "last.terminalized")
                .gauge().value()).isEqualTo(2);
    }

    @Test
    void rejectsUnboundedWorkerConfiguration() {
        MirrorSessionStateStore store =
                mock(MirrorSessionStateStore.class);
        MirrorSessionCapacityTelemetry telemetry =
                MirrorSessionCapacityTelemetry.noop();

        assertThatThrownBy(() ->
                new MirrorSessionWriteAttemptReconciliationScheduler(
                        store, telemetry, 0, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new MirrorSessionWriteAttemptReconciliationScheduler(
                        store, telemetry, 10, 999))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double counter(
            SimpleMeterRegistry meters, String outcome) {
        return meters.get(SWEEP_METER)
                .tag("outcome", outcome)
                .counter().count();
    }
}
