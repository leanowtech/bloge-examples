package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationExternalArchiveFindingRetentionSchedulerTest {

    @Test
    void returnsCompletedAndBusyAttemptsWithoutChangingPolicy() {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .class);
        var completed = DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                .RetentionAttempt.completed(new
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                                .RetentionResult(
                                2, 1, 3, 4, false, "", Instant.now()));
        when(controlPlane.retain(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365), 100))
                .thenReturn(completed)
                .thenReturn(DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .RetentionAttempt.busy());
        var scheduler = scheduler(controlPlane);

        assertThat(scheduler.retain()).isEqualTo(completed);
        assertThat(scheduler.retain().status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .RetentionStatus.LEASE_BUSY);
        verify(controlPlane, times(2)).retain(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365), 100);
    }

    @Test
    void containsOneFailureAndRetriesOnTheNextScheduledInvocation() {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .class);
        when(controlPlane.retain(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365), 100))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .RetentionAttempt.busy());
        var scheduler = scheduler(controlPlane);

        assertThat(scheduler.retain()).isNull();
        assertThat(scheduler.retain().status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .RetentionStatus.LEASE_BUSY);
    }

    @Test
    void rejectsUnsafeRetentionAndUnboundedPages() {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .class);

        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
                controlPlane, Duration.ofMinutes(59), Duration.ofDays(1),
                Duration.ofDays(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
                controlPlane, Duration.ofHours(1), Duration.ofHours(23),
                Duration.ofDays(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
                controlPlane, Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(1), 501)).isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler scheduler(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                    controlPlane) {
        return new TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler(
                controlPlane, Duration.ofDays(30), Duration.ofDays(365),
                Duration.ofDays(365), 100);
    }
}
