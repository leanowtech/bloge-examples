package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeContinuousAssessmentSchedulerPropertiesTest {
    @Test
    void keepsDisabledDefaultsCredentialFreeAndBounded() {
        AuthoritativeOutcomeContinuousAssessmentSchedulerProperties
                properties =
                new AuthoritativeOutcomeContinuousAssessmentSchedulerProperties(
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
        assertThat(properties.maximumPollers())
                .isEqualTo(2);
        assertThat(properties.pollIntervalMillis())
                .isEqualTo(1_000L);
    }

    @Test
    void validatesEnabledPartitionAndRejectsProduction() {
        AuthoritativeOutcomeContinuousAssessmentSchedulerProperties
                enabled =
                new AuthoritativeOutcomeContinuousAssessmentSchedulerProperties(
                        true,
                        "instance-1",
                        "SG",
                        "Staging",
                        4,
                        0L,
                        250L,
                        5_000L);

        assertThat(enabled.region()).isEqualTo("sg");
        assertThat(enabled.environmentId())
                .isEqualTo("staging");
        assertThat(enabled.maximumPollers())
                .isEqualTo(4);
        assertThatThrownBy(() ->
                new AuthoritativeOutcomeContinuousAssessmentSchedulerProperties(
                        true,
                        "instance-1",
                        "sg",
                        "production",
                        1,
                        0L,
                        1_000L,
                        5_000L))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }

    @Test
    void rejectsPartialEnabledAndUnboundedControls() {
        assertThatThrownBy(() ->
                new AuthoritativeOutcomeContinuousAssessmentSchedulerProperties(
                        true,
                        "",
                        "sg",
                        "staging",
                        1,
                        0L,
                        1_000L,
                        5_000L))
                .isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AuthoritativeOutcomeContinuousAssessmentSchedulerProperties(
                        false,
                        "",
                        "",
                        "",
                        65,
                        0L,
                        1_000L,
                        5_000L))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
