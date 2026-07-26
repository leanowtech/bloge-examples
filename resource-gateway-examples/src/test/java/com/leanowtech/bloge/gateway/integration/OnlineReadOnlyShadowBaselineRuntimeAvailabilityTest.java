package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineReadOnlyShadowBaselineRuntimeAvailabilityTest {

    @Test
    void samplesEachDependencyOnceAndSeparatesReadinessFacts() {
        AtomicBoolean authority = new AtomicBoolean(false);
        AtomicBoolean evidence = new AtomicBoolean(true);
        AtomicInteger authorityProbes = new AtomicInteger();
        AtomicInteger evidenceProbes = new AtomicInteger();
        var availability =
                new OnlineReadOnlyShadowBaselineRuntimeAvailability(
                        true,
                        () -> {
                            authorityProbes.incrementAndGet();
                            return authority.get();
                        },
                        () -> {
                            evidenceProbes.incrementAndGet();
                            return evidence.get();
                        });

        assertThat(availability.snapshot())
                .isEqualTo(
                        new OnlineReadOnlyShadowBaselineRuntimeAvailability
                                .Snapshot(
                                true,
                                false,
                                true,
                                false));
        assertThat(authorityProbes).hasValue(1);
        assertThat(evidenceProbes).hasValue(1);

        authority.set(true);
        assertThat(availability.snapshot().baselineReady())
                .isTrue();
    }

    @Test
    void doesNotProbeAndFailsClosedWhenConnectorIsAbsentOrProviderFails() {
        AtomicInteger probes = new AtomicInteger();
        var absent =
                new OnlineReadOnlyShadowBaselineRuntimeAvailability(
                        false,
                        () -> {
                            probes.incrementAndGet();
                            return true;
                        },
                        () -> {
                            probes.incrementAndGet();
                            return true;
                        });

        assertThat(absent.snapshot())
                .isEqualTo(
                        new OnlineReadOnlyShadowBaselineRuntimeAvailability
                                .Snapshot(
                                false,
                                false,
                                false,
                                false));
        assertThat(probes).hasValue(0);

        var failing =
                new OnlineReadOnlyShadowBaselineRuntimeAvailability(
                        true,
                        () -> {
                            throw new IllegalStateException(
                                    "sidecar unavailable");
                        },
                        () -> {
                            throw new IllegalStateException(
                                    "trust unavailable");
                        });
        assertThat(failing.snapshot())
                .isEqualTo(
                        new OnlineReadOnlyShadowBaselineRuntimeAvailability
                                .Snapshot(
                                true,
                                false,
                                false,
                                false));
    }
}
