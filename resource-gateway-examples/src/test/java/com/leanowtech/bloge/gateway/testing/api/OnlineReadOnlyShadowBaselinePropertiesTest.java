package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineReadOnlyShadowBaselinePropertiesTest {

    @Test
    void remainsDisabledWithConservativeFiniteDefaults() {
        OnlineReadOnlyShadowBaselineProperties properties =
                new OnlineReadOnlyShadowBaselineProperties(
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.requestTimeoutMillis())
                .isEqualTo(5_000);
        assertThat(properties.maximumResponseBytes())
                .isEqualTo(512 * 1024);
        assertThatThrownBy(properties::settings)
                .isInstanceOf(
                        IllegalStateException.class);
    }

    @Test
    void acceptsBoundedHttpsAndRejectsUnsafeOrConflictingModes() {
        OnlineReadOnlyShadowBaselineProperties properties =
                new OnlineReadOnlyShadowBaselineProperties(
                        true,
                        "https://baseline.ap.example.test/",
                        2_000L,
                        64 * 1024,
                        false);

        assertThat(properties.settings()
                .baseUri().toString())
                .isEqualTo(
                        "https://baseline.ap.example.test");
        assertThatThrownBy(() ->
                new OnlineReadOnlyShadowBaselineProperties(
                        true,
                        "http://baseline.example.test",
                        2_000L,
                        64 * 1024,
                        false))
                .isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new OnlineReadOnlyShadowBaselineProperties(
                        true,
                        "https://baseline.example.test",
                        31_000L,
                        64 * 1024,
                        false))
                .isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ReadOnlyShadowDataPlaneModeSelection(
                        true, true))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "mutually exclusive");
    }
}
