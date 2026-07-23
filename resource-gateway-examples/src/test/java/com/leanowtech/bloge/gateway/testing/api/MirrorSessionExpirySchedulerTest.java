package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCapacityTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MirrorSessionExpirySchedulerTest {

    @Test
    void runsOneBoundedPageAndConvertsFailuresToTelemetry() {
        MirrorSessionStateStore store = mock(MirrorSessionStateStore.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorSessionCapacityTelemetry telemetry =
                new MirrorSessionCapacityTelemetry(meters);
        MirrorSessionExpiryScheduler scheduler =
                new MirrorSessionExpiryScheduler(
                        store, telemetry, 25, 30_000);
        when(store.expireDue(25)).thenReturn(7)
                .thenThrow(new IllegalStateException("secret"));

        scheduler.sweep();
        scheduler.sweep();

        verify(store, org.mockito.Mockito.times(2)).expireDue(25);
        assertThat(meters.get(
                "resource.gateway.mirror.session.expiry.sweeps")
                .tag("outcome", "succeeded")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(
                "resource.gateway.mirror.session.expiry.sweeps")
                .tag("outcome", "failed")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get(
                "resource.gateway.mirror.session.expiry.last.expired.sessions")
                .gauge().value()).isEqualTo(7);
    }

    @Test
    void skipsAReplicaLocalOverlappingSweep() throws Exception {
        MirrorSessionStateStore store = mock(MirrorSessionStateStore.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorSessionCapacityTelemetry telemetry =
                new MirrorSessionCapacityTelemetry(meters);
        MirrorSessionExpiryScheduler scheduler =
                new MirrorSessionExpiryScheduler(
                        store, telemetry, 25, 30_000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.expireDue(25)).thenAnswer(ignored -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            return 1;
        });

        Thread first = Thread.startVirtualThread(scheduler::sweep);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.sweep();
        release.countDown();
        first.join(5_000);

        assertThat(first.isAlive()).isFalse();
        verify(store).expireDue(25);
        assertThat(meters.get(
                "resource.gateway.mirror.session.expiry.sweeps")
                .tag("outcome", "skipped")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void rejectsAnUnboundedBatchAtAssemblyTime() {
        MirrorSessionStateStore store = mock(MirrorSessionStateStore.class);
        MirrorSessionCapacityTelemetry telemetry =
                MirrorSessionCapacityTelemetry.noop();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new MirrorSessionExpiryScheduler(
                        store, telemetry, 0, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new MirrorSessionExpiryScheduler(
                        store, telemetry, 1_001, 30_000))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new MirrorSessionExpiryScheduler(
                        store, telemetry, 100, 999))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new MirrorSessionExpiryScheduler(
                        store, telemetry, 100, 3_600_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
