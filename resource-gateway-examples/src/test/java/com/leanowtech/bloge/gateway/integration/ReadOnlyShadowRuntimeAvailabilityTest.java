package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowRuntimeAvailabilityTest {

    @Test
    void keepsControlDataAndSchedulerReadinessIndependent() {
        AtomicBoolean worker =
                new AtomicBoolean();
        AtomicBoolean scheduler =
                new AtomicBoolean();
        ReadOnlyShadowRuntimeAvailability availability =
                new ReadOnlyShadowRuntimeAvailability(
                        true,
                        true,
                        worker::get,
                        scheduler::get);

        assertThat(availability.jobApi()).isTrue();
        assertThat(availability.lifecycleAudit())
                .isTrue();
        assertThat(availability.servingReady())
                .isFalse();
        worker.set(true);
        assertThat(availability.workerReady())
                .isTrue();
        assertThat(availability.servingReady())
                .isFalse();
        scheduler.set(true);
        assertThat(availability.schedulerReady())
                .isTrue();
        assertThat(availability.servingReady())
                .isTrue();
    }

    @Test
    void readinessProbeExceptionsFailClosed() {
        ReadOnlyShadowRuntimeAvailability availability =
                new ReadOnlyShadowRuntimeAvailability(
                        true,
                        true,
                        () -> {
                            throw new IllegalStateException(
                                    "worker unavailable");
                        },
                        () -> {
                            throw new IllegalStateException(
                                    "scheduler unavailable");
                        });

        assertThat(availability.workerReady())
                .isFalse();
        assertThat(availability.schedulerReady())
                .isFalse();
        assertThat(availability.servingReady())
                .isFalse();
    }
}
