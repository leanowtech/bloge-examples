package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchFinalizationSchedulerPropertiesTest {

    @Test
    void staysDisabledByDefaultWithOneConservativeKmsLane() {
        ScenarioRehearsalBatchFinalizationSchedulerProperties
                properties =
                new ScenarioRehearsalBatchFinalizationSchedulerProperties(
                        null, null, null, null,
                        null, null, null, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.instanceId()).isBlank();
        assertThat(properties.maximumPollers()).isEqualTo(1);
        assertThat(properties.initialDelay())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.pollInterval())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.drainTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void acceptsOnlyAnExactBoundedNonProductionPartition() {
        ScenarioRehearsalBatchFinalizationSchedulerProperties
                properties =
                new ScenarioRehearsalBatchFinalizationSchedulerProperties(
                        true,
                        "replica-a",
                        "SG",
                        "STAGING",
                        2,
                        0L,
                        250L,
                        5_000L);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.region()).isEqualTo("sg");
        assertThat(properties.environmentId())
                .isEqualTo("staging");
        assertThat(properties.maximumPollers()).isEqualTo(2);

        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationSchedulerProperties(
                        true,
                        "replica-a",
                        "sg",
                        "production",
                        1,
                        0L,
                        1_000L,
                        5_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationSchedulerProperties(
                        true,
                        "replica-a",
                        "sg",
                        "test",
                        33,
                        0L,
                        1_000L,
                        5_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
