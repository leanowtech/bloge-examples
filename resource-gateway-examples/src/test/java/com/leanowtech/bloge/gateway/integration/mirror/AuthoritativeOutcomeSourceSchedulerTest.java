package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeSourceSchedulerTest {
    @Test
    void pollsBoundedLaneAndStopsReadinessOnClose() {
        AuthoritativeOutcomeSourceWorker worker = mock(
                AuthoritativeOutcomeSourceWorker.class);
        when(worker.runOne("sg", "staging", "instance-a/lane-1"))
                .thenReturn(AuthoritativeOutcomeSourceCheckpointRepository.Claim.noWork(
                        java.time.Instant.parse("2026-08-03T00:00:00Z")));
        var scheduler = new AuthoritativeOutcomeSourceScheduler(
                worker, "sg", "staging", "instance-a", 1,
                Duration.ZERO, Duration.ofMillis(100), Duration.ofSeconds(1));

        verify(worker, timeout(2_000).atLeastOnce()).runOne(
                eq("sg"), eq("staging"), eq("instance-a/lane-1"));
        assertThat(scheduler.ready()).isTrue();

        scheduler.close();
        assertThat(scheduler.ready()).isFalse();
        assertThat(scheduler.activePolls()).isZero();
    }

    @Test
    void refusesReservedProductionAndUnboundedLaneCounts() {
        AuthoritativeOutcomeSourceWorker worker = mock(
                AuthoritativeOutcomeSourceWorker.class);

        assertThatThrownBy(() -> new AuthoritativeOutcomeSourceScheduler(
                worker, "sg", "production", "instance-a", 1,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production");
        assertThatThrownBy(() -> new AuthoritativeOutcomeSourceScheduler(
                worker, "sg", "staging", "instance-a", 65,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollers");
    }
}
