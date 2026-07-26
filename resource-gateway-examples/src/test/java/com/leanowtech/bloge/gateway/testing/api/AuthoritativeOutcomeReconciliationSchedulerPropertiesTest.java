package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeReconciliationSchedulerPropertiesTest {

    @Test
    void remainsDisabledWithFiniteDefaults() {
        AuthoritativeOutcomeReconciliationSchedulerProperties properties =
                new AuthoritativeOutcomeReconciliationSchedulerProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maximumPollers()).isEqualTo(2);
        assertThat(properties.pollIntervalMillis())
                .isEqualTo(1_000);
    }

    @Test
    void acceptsEnterpriseEnvironmentNamesAndRejectsProductionAliases() {
        AuthoritativeOutcomeReconciliationSchedulerProperties properties =
                new AuthoritativeOutcomeReconciliationSchedulerProperties(
                        true,
                        "replica-a",
                        "SG",
                        "OUTCOME-STAGING",
                        4,
                        0L,
                        100L,
                        1_000L);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.region()).isEqualTo("sg");
        assertThat(properties.environmentId())
                .isEqualTo("outcome-staging");
        assertThatThrownBy(() ->
                new AuthoritativeOutcomeReconciliationSchedulerProperties(
                        true,
                        "replica-a",
                        "sg",
                        "production",
                        1,
                        0L,
                        100L,
                        1_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
