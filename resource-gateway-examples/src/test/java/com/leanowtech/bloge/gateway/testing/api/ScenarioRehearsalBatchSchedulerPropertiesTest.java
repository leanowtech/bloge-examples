package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchSchedulerPropertiesTest {

    @Test
    void keepsSchedulingDisabledByDefaultWithoutRequiringAResidualIdentity() {
        ScenarioRehearsalBatchSchedulerProperties properties =
                new ScenarioRehearsalBatchSchedulerProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.instanceId()).isBlank();
        assertThat(properties.maximumPollers()).isEqualTo(4);
        assertThat(properties.initialDelay())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.pollInterval())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.drainTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void acceptsOneExactBoundedNonProductionPartition() {
        ScenarioRehearsalBatchSchedulerProperties properties =
                new ScenarioRehearsalBatchSchedulerProperties(
                        true,
                        "replica-a",
                        "SG",
                        "STAGING",
                        8,
                        0L,
                        250L,
                        5_000L);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.region()).isEqualTo("sg");
        assertThat(properties.environmentId())
                .isEqualTo("staging");
        assertThat(properties.maximumPollers()).isEqualTo(8);
    }

    @Test
    void rejectsPartialProductionOrUnboundedActivation() {
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchSchedulerProperties(
                        true,
                        "",
                        "sg",
                        "test",
                        4,
                        1_000L,
                        1_000L,
                        30_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchSchedulerProperties(
                        true,
                        "replica-a",
                        "sg",
                        "prod",
                        4,
                        1_000L,
                        1_000L,
                        30_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchSchedulerProperties(
                        true,
                        "replica-a",
                        "sg",
                        "test",
                        257,
                        1_000L,
                        1_000L,
                        30_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
