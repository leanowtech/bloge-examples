package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitorTest {

    private static final String STARTUP_ID =
            "00000000-0000-0000-0000-000000000001";

    @Test
    void performsImmediateHeartbeatRenewsAndWithdrawsExactProcessStart() {
        var repository = mock(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository.class);
        when(repository.leaseDuration()).thenReturn(Duration.ofSeconds(4));
        var monitor = new TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
                repository, Duration.ofSeconds(1), STARTUP_ID, false);

        verify(repository).heartbeat(STARTUP_ID);
        assertThat(monitor.startupId()).isEqualTo(STARTUP_ID);
        assertThat(monitor.heartbeatNow()).isTrue();
        verify(repository, times(2)).heartbeat(STARTUP_ID);

        monitor.close();
        verify(repository).withdraw(STARTUP_ID);
        assertThat(monitor.heartbeatNow()).isFalse();
        monitor.close();
        verify(repository).leaseDuration();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsInvalidIntervalsAndProcessIdentityBeforeRegistration() {
        var repository = mock(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository.class);
        when(repository.leaseDuration()).thenReturn(Duration.ofSeconds(4));

        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
                repository, Duration.ofMillis(100), STARTUP_ID, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
                repository, Duration.ofSeconds(3), STARTUP_ID, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
                repository, Duration.ofSeconds(1), "not-a-uuid", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, times(3)).leaseDuration();
        verifyNoMoreInteractions(repository);
    }
}
