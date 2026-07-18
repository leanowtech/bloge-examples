package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobRetentionSchedulerTest {

    @Test
    void delegatesExactPolicyAndKeepsLaterTicksAliveAfterBusyAndFailure() {
        TestSuiteStabilityJobRepository repository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry telemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        when(repository.retainExpired(Duration.ofDays(365), 100))
                .thenReturn(TestSuiteStabilityJobRetentionAttempt.leaseBusy())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(completedAttempt());
        when(repository.observeRetention()).thenReturn(snapshot());
        var scheduler = new TestSuiteStabilityJobRetentionScheduler(
                repository, Duration.ofDays(365), 100,
                telemetry, Duration.ofHours(1));

        scheduler.retain();
        scheduler.retain();
        scheduler.retain();

        verify(repository, times(3)).retainExpired(Duration.ofDays(365), 100);
        verify(telemetry, times(2)).record(any(), any(Duration.class));
        verify(telemetry).recordFailure(any(Duration.class));
        verify(telemetry).refresh(snapshot());
    }

    @Test
    void telemetryFailuresDoNotReclassifyACommittedPageOrStopTheLoop() {
        TestSuiteStabilityJobRepository repository =
                mock(TestSuiteStabilityJobRepository.class);
        TestSuiteStabilityJobRetentionTelemetry telemetry =
                mock(TestSuiteStabilityJobRetentionTelemetry.class);
        when(repository.retainExpired(Duration.ofDays(365), 100))
                .thenReturn(completedAttempt());
        when(repository.observeRetention())
                .thenThrow(new IllegalStateException("snapshot unavailable"));
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(telemetry).record(any(), any(Duration.class));
        var scheduler = new TestSuiteStabilityJobRetentionScheduler(
                repository, Duration.ofDays(365), 100,
                telemetry, Duration.ofHours(1));

        scheduler.retain();

        verify(repository).observeRetention();
        verify(telemetry, never()).recordFailure(any(Duration.class));
    }

    @Test
    void rejectsUnsafeOrUnboundedLifecyclePolicyAtAssemblyTime() {
        TestSuiteStabilityJobRepository repository =
                mock(TestSuiteStabilityJobRepository.class);

        assertThatThrownBy(() -> new TestSuiteStabilityJobRetentionScheduler(
                repository, Duration.ofHours(23), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstoneRetention");
        assertThatThrownBy(() -> new TestSuiteStabilityJobRetentionScheduler(
                repository, Duration.ofDays(365), 1_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> new TestSuiteStabilityJobRetentionScheduler(
                repository, Duration.ofDays(365), 100,
                TestSuiteStabilityJobRetentionTelemetry.noop(), Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleInterval");
    }

    private static TestSuiteStabilityJobRetentionAttempt completedAttempt() {
        return TestSuiteStabilityJobRetentionAttempt.completed(
                new TestSuiteStabilityJobRetentionResult(
                        4, 3, Instant.parse("2026-07-18T08:00:00Z")));
    }

    private static TestSuiteStabilityJobRetentionSnapshot snapshot() {
        Instant now = Instant.parse("2026-07-18T08:00:00Z");
        return new TestSuiteStabilityJobRetentionSnapshot(
                "", 1, Instant.EPOCH, 2, 4, 3,
                5, 3, 0, 0, null, null, now, now);
    }
}
