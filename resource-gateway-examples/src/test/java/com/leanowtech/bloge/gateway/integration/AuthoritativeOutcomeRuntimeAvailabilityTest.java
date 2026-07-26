package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeRuntimeAvailabilityTest {

    @Test
    void publishesIndependentFactsAndRequiresAllForContinuousReadiness() {
        AtomicBoolean connector = new AtomicBoolean(true);
        AtomicBoolean worker = new AtomicBoolean(true);
        AtomicBoolean scheduler = new AtomicBoolean(true);
        AuthoritativeOutcomeRuntimeAvailability availability =
                new AuthoritativeOutcomeRuntimeAvailability(
                        true,
                        true,
                        connector::get,
                        worker::get,
                        scheduler::get);

        assertThat(availability.inboxApi()).isTrue();
        assertThat(availability.lifecycleAudit()).isTrue();
        assertThat(availability.continuousReady()).isTrue();

        connector.set(false);
        assertThat(availability.connectorReady()).isFalse();
        assertThat(availability.workerReady()).isTrue();
        assertThat(availability.schedulerReady()).isTrue();
        assertThat(availability.continuousReady()).isFalse();
    }

    @Test
    void failedDependencyProbeFailsClosed() {
        AuthoritativeOutcomeRuntimeAvailability availability =
                new AuthoritativeOutcomeRuntimeAvailability(
                        true,
                        true,
                        () -> {
                            throw new IllegalStateException("unavailable");
                        },
                        () -> true,
                        () -> true);

        assertThat(availability.connectorReady()).isFalse();
        assertThat(availability.continuousReady()).isFalse();
    }
}
