package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineReadOnlyShadowCandidatePropertiesTest {

    @Test
    void remainsDisabledWithConservativeFiniteDefaults() {
        OnlineReadOnlyShadowCandidateProperties properties =
                new OnlineReadOnlyShadowCandidateProperties(
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.requestTimeoutMillis())
                .isEqualTo(5_000);
        assertThat(properties.maximumResponseBytes())
                .isEqualTo(8 * 1024 * 1024);
        assertThatThrownBy(properties::settings)
                .isInstanceOf(
                        IllegalStateException.class);
    }

    @Test
    void acceptsBoundedHttpsAndRejectsUnsafeSettings() {
        OnlineReadOnlyShadowCandidateProperties properties =
                new OnlineReadOnlyShadowCandidateProperties(
                        true,
                        "https://candidate.ap.example.test/",
                        2_000L,
                        2 * 1024 * 1024,
                        false);

        assertThat(properties.settings()
                .baseUri().toString())
                .isEqualTo(
                        "https://candidate.ap.example.test");
        assertThatThrownBy(() ->
                new OnlineReadOnlyShadowCandidateProperties(
                        true,
                        "http://candidate.example.test",
                        2_000L,
                        2 * 1024 * 1024,
                        false))
                .isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new OnlineReadOnlyShadowCandidateProperties(
                        true,
                        "https://candidate.example.test",
                        31_000L,
                        2 * 1024 * 1024,
                        false))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }
}
