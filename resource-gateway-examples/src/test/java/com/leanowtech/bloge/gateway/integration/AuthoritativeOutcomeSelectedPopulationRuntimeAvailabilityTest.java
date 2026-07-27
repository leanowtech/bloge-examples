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
                        true,
                        true,
                        true,
                        authorities::get,
                        () -> true,
                        () -> true);

        assertThat(availability.api()).isTrue();
        assertThat(availability.durableRegistry())
                .isTrue();
        assertThat(availability.sourceClosure())
                .isTrue();
        assertThat(availability.continuousAssessmentApi())
                .isTrue();
        assertThat(availability.durableProjection())
                .isTrue();
        assertThat(availability.projectionWorkerReady())
                .isTrue();
        assertThat(availability.projectionSchedulerReady())
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
    void allAssemblyAndSchedulingFactsAreRequiredForContinuousReadiness() {
        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability
                availability =
                new
                        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
                        true,
                        true,
                        true,
                        false,
                        () -> true);

        assertThat(availability.stagedUpload()).isFalse();
        assertThat(availability.continuousReady()).isFalse();

        AuthoritativeOutcomeSelectedPopulationRuntimeAvailability
                unscheduled =
                new AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        () -> true,
                        () -> true,
                        () -> false);
        assertThat(unscheduled.continuousReady())
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
                        true,
                        true,
                        true,
                        () -> {
                            throw new IllegalStateException(
                                    "authority unavailable");
                        },
                        () -> true,
                        () -> true);

        assertThat(availability.authoritiesReady())
                .isFalse();
        assertThat(availability.continuousReady())
                .isFalse();
    }
}
