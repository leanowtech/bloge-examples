package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalBatchFinalizationPolicyTest {

    @Test
    void capsOverflowSafeExponentialBackoff() {
        ScenarioRehearsalBatchFinalizationPolicy policy =
                new ScenarioRehearsalBatchFinalizationPolicy(
                        2,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(10),
                        8);

        assertThat(policy.retryBackoff(1))
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(policy.retryBackoff(2))
                .isEqualTo(Duration.ofSeconds(6));
        assertThat(policy.retryBackoff(3))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.retryBackoff(10_000))
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsUnboundedLeaseRetryAndAttemptBudgets() {
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationPolicy(
                        0,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationPolicy(
                        1,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationPolicy(
                        1,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ScenarioRehearsalBatchFinalizationPolicy(
                        1,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
