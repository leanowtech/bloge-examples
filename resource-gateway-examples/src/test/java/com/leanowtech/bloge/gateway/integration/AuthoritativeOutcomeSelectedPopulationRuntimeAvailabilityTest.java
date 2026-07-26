package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeSelectedPopulationRuntimeAvailabilityTest {

    @Test
    void separatesAssemblyFactsFromDynamicAuthorityReadiness() {
        AtomicBoolean authorities =
                new AtomicBoolean(true);
        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability
                availability =
                new
                        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
                        true,
                        true,
                        true,
                        authorities::get);

        assertThat(availability.api()).isTrue();
        assertThat(availability.durableRegistry())
                .isTrue();
        assertThat(availability.sourceClosure())
                .isTrue();
        assertThat(availability.authoritiesReady())
                .isTrue();
        assertThat(availability.continuousReady())
                .isTrue();

        authorities.set(false);
        assertThat(availability.api()).isTrue();
        assertThat(availability.continuousReady())
                .isFalse();
    }

    @Test
    void failedAuthorityProbeFailsClosed() {
        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability
                availability =
                new
                        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
                        true,
                        true,
                        true,
                        () -> {
                            throw new IllegalStateException(
                                    "authority unavailable");
                        });

        assertThat(availability.authoritiesReady())
                .isFalse();
        assertThat(availability.continuousReady())
                .isFalse();
    }
}
