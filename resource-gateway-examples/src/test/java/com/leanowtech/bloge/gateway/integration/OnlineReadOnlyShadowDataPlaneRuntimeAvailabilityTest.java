package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineReadOnlyShadowDataPlaneRuntimeAvailabilityTest {

    @Test
    void distinguishesEveryOnlineCandidateAndResolverReadinessLayer() {
        OnlineReadOnlyShadowDataPlaneRuntimeAvailability
                availability =
                new OnlineReadOnlyShadowDataPlaneRuntimeAvailability(
                        true,
                        true,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true);

        assertThat(availability.snapshot())
                .isEqualTo(
                        new OnlineReadOnlyShadowDataPlaneRuntimeAvailability
                                .Snapshot(
                                true,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true));
    }

    @Test
    void samplesEachDependencyOnceAndFailsClosedOnExceptions() {
        AtomicInteger authorityCalls =
                new AtomicInteger();
        AtomicInteger evidenceCalls =
                new AtomicInteger();
        AtomicInteger resolverCalls =
                new AtomicInteger();
        AtomicInteger dataPlaneCalls =
                new AtomicInteger();
        OnlineReadOnlyShadowDataPlaneRuntimeAvailability
                availability =
                new OnlineReadOnlyShadowDataPlaneRuntimeAvailability(
                        true,
                        true,
                        () -> {
                            authorityCalls.incrementAndGet();
                            return true;
                        },
                        () -> {
                            evidenceCalls.incrementAndGet();
                            throw new IllegalStateException(
                                    "key service unavailable");
                        },
                        () -> {
                            resolverCalls.incrementAndGet();
                            return true;
                        },
                        () -> {
                            dataPlaneCalls.incrementAndGet();
                            return true;
                        });

        OnlineReadOnlyShadowDataPlaneRuntimeAvailability.Snapshot
                snapshot =
                availability.snapshot();

        assertThat(snapshot.candidateReady()).isFalse();
        assertThat(snapshot.pairedResolverReady()).isTrue();
        assertThat(snapshot.dataPlaneReady()).isFalse();
        assertThat(authorityCalls).hasValue(1);
        assertThat(evidenceCalls).hasValue(1);
        assertThat(resolverCalls).hasValue(1);
        assertThat(dataPlaneCalls).hasValue(1);
    }
}
