package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSourceSchedulerPropertiesTest {
    @Test
    void appliesBoundedDisabledDefaults() {
        var properties = new AuthoritativeOutcomeSourceSchedulerProperties(
                null, null, null, null, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maximumPollers()).isEqualTo(2);
        assertThat(properties.pollIntervalMillis()).isEqualTo(1_000L);
    }

    @Test
    void enabledSchedulerRequiresExactNonProductionCoordinates() {
        assertThatThrownBy(() -> new AuthoritativeOutcomeSourceSchedulerProperties(
                true, "instance-a", "sg", "production",
                2, 0L, 1_000L, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production");
        assertThatThrownBy(() -> new AuthoritativeOutcomeSourceSchedulerProperties(
                true, "", "sg", "staging",
                2, 0L, 1_000L, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
    }
}
