package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSequenceAnchorBootstrapRootCeremonyRecoverySchedulerTest {

    @Test
    void synchronousFailureIsCountedOnceAndTheNextBoundedResultClearsLatestFailure() {
        var service = mock(ExternalSequenceAnchorBootstrapRootCeremonyService.class);
        var resolver = mock(ExternalSequenceAnchorBootstrapRootAuthorityResolver.class);
        when(service.recover(anyString(), anyLong(), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(noWork());
        try (var scheduler = new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
                service, "recovery-worker", 30, resolver,
                new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.SchedulePolicy(
                        Duration.ofDays(1), Duration.ofHours(1), Duration.ZERO))) {
            assertThatThrownBy(scheduler::runOnce)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isOne();
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isTrue();
            });

            assertThat(scheduler.runOnce().status()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                            .NO_ACTIVE_CEREMONY);
            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isEqualTo(2L);
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isFalse();
            });
        }
    }

    @Test
    void scheduledFatalFailureRemainsVisibleAfterTheScheduledFutureStops() throws Exception {
        var service = mock(ExternalSequenceAnchorBootstrapRootCeremonyService.class);
        var resolver = mock(ExternalSequenceAnchorBootstrapRootAuthorityResolver.class);
        when(service.recover(anyString(), anyLong(), any()))
                .thenThrow(new AssertionError("fatal journal failure"));
        try (var scheduler = new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
                service, "recovery-worker", 30, resolver,
                new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.SchedulePolicy(
                        Duration.ZERO, Duration.ofHours(1), Duration.ZERO))) {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (scheduler.snapshot().pollFailureCount() == 0L
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollCount()).isOne();
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isTrue();
            });
        }
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult
            noWork() {
        return new ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .NO_ACTIVE_CEREMONY,
                null, null, null);
    }
}
