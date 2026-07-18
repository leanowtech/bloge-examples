package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityLeaseCoordinatorTest {

    @Test
    void terminalCheckpointPerformsSynchronousRenewalAndConsumedLeaseIsNotReleased() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityExecutionLease initial = lease("owner-a", 0, Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease renewed = lease("owner-a", 0, Instant.now().plusSeconds(60));
        when(repository.renew(initial, Duration.ofSeconds(30)))
                .thenReturn(Optional.of(renewed));
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        try (TestSuiteStabilityLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(initial)) {
            assertThat(guard.checkpoint()).isEqualTo(renewed);
            guard.consumed();
        }

        verify(repository).renew(initial, Duration.ofSeconds(30));
        verify(repository, never()).release(renewed);
    }

    @Test
    void ambiguousRenewalPermanentlyLosesTheLocalGuardAndCloseCannotMaskIt() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityExecutionLease initial = lease("owner-a", 0, Instant.now().plusSeconds(30));
        when(repository.renew(initial, Duration.ofSeconds(30))).thenReturn(Optional.empty());
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        try (TestSuiteStabilityLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(initial)) {
            assertThatThrownBy(guard::checkpoint)
                    .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
            assertThatThrownBy(guard::checkpoint)
                    .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        }

        verify(repository).release(initial);
    }

    @Test
    void configurationRejectsHeartbeatThatCannotRenewBeforeOneThirdLease() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);

        assertThatThrownBy(() -> new TestSuiteStabilityLeaseCoordinator(
                repository, "replica-a", Duration.ofSeconds(30), Duration.ofSeconds(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-third");
        assertThatThrownBy(() -> new TestSuiteStabilityLeaseCoordinator(
                repository, "replica-a", Duration.ofSeconds(4), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 to 3600");
    }

    @Test
    void claimRequestsUseFreshInvocationOwnersButOneConfiguredDuration() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        TestSuiteStabilityLeaseRequest first = coordinator.request(
                runId(), "tenant-a", "test", "request-a", fingerprint('a'));
        TestSuiteStabilityLeaseRequest second = coordinator.request(
                runId(), "tenant-a", "test", "request-a", fingerprint('a'));

        assertThat(first.ownerId()).isNotEqualTo(second.ownerId());
        assertThat(first.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(second.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void coordinatorShutdownInvalidatesEveryActiveGuardBeforeReleasingItsLease() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityExecutionLease initial = lease(
                "owner-a", 0, Instant.now().plusSeconds(30));
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));
        TestSuiteStabilityLeaseCoordinator.LeaseGuard guard = coordinator.monitor(initial);

        coordinator.close();

        assertThatThrownBy(guard::checkpoint)
                .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        assertThatThrownBy(() -> coordinator.monitor(initial))
                .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        assertThatThrownBy(() -> coordinator.request(
                runId(), "tenant-a", "test", "request-a", fingerprint('a')))
                .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        verify(repository).release(initial);
    }

    private static TestSuiteStabilityExecutionLease lease(
            String owner,
            long epoch,
            Instant expiresAt) {
        return new TestSuiteStabilityExecutionLease(runId(), "tenant-a", "test", "request-a",
                fingerprint('a'), owner, epoch, expiresAt);
    }

    private static String runId() {
        return "stability-" + "1".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
