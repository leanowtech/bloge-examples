package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchFinalizationSloPropertiesTest {

    @Test
    void appliesConservativeBoundedDefaults() {
        ScenarioRehearsalBatchFinalizationSloProperties
                properties =
                new ScenarioRehearsalBatchFinalizationSloProperties(
                        null, null, null, null,
                        null, null, null, null);

        assertThat(properties.policy()
                .observationInterval())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.policy()
                .maximumEligibleBacklog()).isEqualTo(100);
        assertThat(properties.policy()
                .maximumOldestEligibleAge())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.policy()
                .maximumQuarantinedBacklog()).isZero();
        assertThat(properties.policy()
                .criticalQuarantinedBacklog())
                .isEqualTo(100);
    }

    @Test
    void rejectsContradictoryOrUnboundedDeploymentThresholds() {
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationSloProperties(
                        500L,
                        100L,
                        300L,
                        90L,
                        10L,
                        10L,
                        10L,
                        10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationSloProperties(
                        30_000L,
                        -1L,
                        300L,
                        90L,
                        0L,
                        100L,
                        10L,
                        10L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
